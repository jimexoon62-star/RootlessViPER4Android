package me.timschneeberger.rootlessjamesdsp.service

import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.os.*
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.*
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.*
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.room.*
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionManager
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.utils.*
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.notifications.*
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.*

/** Full native engine with explicitly authorized shell routing. No muting effects.
 * One generation owns capture/output/engine; a renderer lease bounds lost routing. */
class ShellAudioProcessorService : BaseAudioProcessorService() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob())
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val prefs by lazy { getSharedPreferences(Constants.PREF_APP, MODE_PRIVATE) }
    private val blockedApps by lazy { AppBlocklistRepository(AppBlocklistDatabase.getDatabase(this, scope).appBlocklistDao()).blocklist.asLiveData() }
    private var blocked = emptySet<Int>()
    private var manager: BaseSessionManager? = null
    private var dump: ISessionInfoDump? = null
    private val settingsRevision = AtomicLong()
    private var initialized = false
    @Volatile private var disposing = false
    private var cleaned = false
    private var selected = emptySet<Int>()
    private var failures = 0
    private var serial = 0L
    @Volatile private var generation: Generation? = null
    private val workerBusy = AtomicBoolean()
    val hasStoppedProcessing get() = disposing
    var currentCaptureStatus = CaptureStatus.CHECKING
        private set
    private class Generation(val id: Long, val rate: Int) {
        val closed = AtomicBoolean()
        @Volatile var source: RoutedPcmSource? = null
        @Volatile var track: AudioTrack? = null
        @Volatile var lastWrite = SystemClock.elapsedRealtime()
        @Volatile var ready = false
        @Volatile var connecting = false
        @Volatile var monitoring = true
        @Volatile var framesIn = 0L
        @Volatile var framesOut = 0L
        var head = -1L
        var headAt = SystemClock.elapsedRealtime()
        var underruns = 0
        var underrunAt = headAt
        var starvationWindows = 0
        var routeId = 0
    }
    private val binderDead = rikka.shizuku.Shizuku.OnBinderDeadListener { handler.post { if (!disposing) { publish(CaptureStatus.DIRECT); requestStop() } } }
    private val blockedObserver = Observer<List<BlockedApp>?> { blocked = it.orEmpty().map { app -> app.uid }.toSet(); reconcile() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MODE_KEY || key == DirectAudioProcessorService.MODE_KEY) requestStop() else reconcile()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            settingsRevision.incrementAndGet()
            if (intent?.action == Constants.ACTION_SERVICE_HARD_REBOOT_CORE || intent?.action == Constants.ACTION_SERVICE_SOFT_REBOOT_CORE) restart("requested engine restart", true)
        }
    }
    private val routes = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = changed(devices)
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = changed(devices)
        private fun changed(devices: Array<out AudioDeviceInfo>) {
            if (devices.any { it.isSink && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX }) {
                retire(generation) // Flush the old route immediately; only re-creation is debounced.
                failures = 0
                handler.removeCallbacks(routeRestart); handler.postDelayed(routeRestart, 500)
            }
        }
    }
    private val trackRouteListener = AudioRouting.OnRoutingChangedListener { routing ->
        val g = generation
        if (g != null && !g.closed.get() && routing === g.track) {
            val device = routing.routedDevice
            if (device != null && device.isSink && device.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                val previous = g.routeId
                g.routeId = device.id
                if (previous != 0 && previous != device.id) {
                    retire(g); failures = 0
                    handler.removeCallbacks(routeRestart); handler.postDelayed(routeRestart, 500)
                }
            }
        }
    }
    private val routeRestart = Runnable { if (!disposing) restart("physical audio route changed", true) }
    private val health = object : Runnable {
        override fun run() {
            if (disposing) return
            val g = generation
            if (g != null && !g.closed.get()) {
                val now = SystemClock.elapsedRealtime()
                if ((g.ready || g.connecting) && now - g.lastWrite > if (g.ready) 1800 else 4000) fail(g, "capture/DSP/output progress timeout")
                else if (g.ready && !g.monitoring) {
                    val track = g.track
                    try {
                        if (track == null || track.state != AudioTrack.STATE_INITIALIZED || track.playState != AudioTrack.PLAYSTATE_PLAYING) throw IOException("Output stopped")
                        val head = track.playbackHeadPosition.toLong() and 0xffffffffL
                        if (head != g.head) { g.head = head; g.headAt = now }
                        else if (now - g.headAt > 2000 && g.framesOut > g.rate / 10) throw IOException("Output frame head stalled")
                        if (now - g.underrunAt >= 2000) {
                            val total = track.underrunCount
                            val delta = (total - g.underruns).coerceAtLeast(0)
                            g.starvationWindows = if (delta > 30) g.starvationWindows + 1 else 0
                            g.underruns = total; g.underrunAt = now
                            if (g.starvationWindows >= 3) throw IOException("Persistent output starvation")
                        }
                        if (failures > 0 && now - g.headAt < 500 && g.framesOut >= g.rate * 30L) failures = 0
                    } catch (e: Exception) { fail(g, e.message ?: "Output health failure") }
                }
            }
            handler.postDelayed(this, 250)
        }
    }
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_SERVICE_STATUS, ServiceNotificationHelper.createServiceNotification(this, arrayOf()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (activeServices > 1 || !requested(this)) { requestStop(); return }
        if (!isAuthorized()) {
            Timber.e("FULL_ENGINE: Shizuku ADB authorization unavailable; no routing created")
            toast("Full ViPER requires Shizuku running in wireless/USB debugging mode and authorization for this app.", true)
            publish(CaptureStatus.DIRECT); requestStop(); return
        }
        initialized = true
        rikka.shizuku.Shizuku.addBinderDeadListener(binderDead)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        val filter = IntentFilter(Constants.ACTION_PREFERENCES_UPDATED).apply {
            addAction(Constants.ACTION_SAMPLE_RATE_UPDATED); addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
            addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE); addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        }
        registerLocalReceiver(receiver, filter)
        blockedApps.observeForever(blockedObserver)
        manager = object : BaseSessionManager(this) {
            // AudioService supplies no native captureFlags. Privileged routing must
            // use AudioPolicy's per-track restrictions without changing user preferences.
            override fun loadSessionDump(): ISessionInfoDump? = dumpManager.dumpCaptureAllowlistLog()
            override fun handleSessionDump(sessionDump: ISessionInfoDump?) { if (!disposing && sessionDump != null) { dump = sessionDump; reconcile() } }
        }
        audio.registerAudioDeviceCallback(routes, handler)
        manager?.pollOnce(false)
        handler.post(health)
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        publish(CaptureStatus.CHECKING)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RootlessAudioProcessorService.ACTION_STOP) requestStop()
        return START_NOT_STICKY
    }
    private fun reconcile() {
        if (!initialized || disposing) return
        val allowlist = prefs.getBoolean(getString(R.string.key_blocklist_allowlist_mode), false)
        val next = ShellRouteSelection.select(dump?.sessions?.values.orEmpty(), android.os.Process.myUid(), packageName, blocked, allowlist)
        if (next != selected) {
            retire(generation); selected = next.toSet(); failures = 0
            publish(CaptureStatus.CHECKING)
            scheduleStart(0)
        }
    }
    private val startTask = Runnable { startGeneration() }
    private fun scheduleStart(delay: Long) { handler.removeCallbacks(startTask); if (!disposing && selected.isNotEmpty()) handler.postDelayed(startTask, delay) }
    private fun startGeneration() {
        if (disposing || selected.isEmpty() || generation != null || failures > 2) return
        // A stuck native invocation must never cause duplicate capture or an engine use-after-free.
        if (!workerBusy.compareAndSet(false, true)) { Timber.e("FULL_ENGINE: retired native worker still active; original playback retained"); publish(CaptureStatus.DIRECT); return }
        val uids = selected.toSet()
        val rate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()?.takeIf { it == 44100 || it == 48000 } ?: 48000
        val g = Generation(++serial, rate); generation = g
        Thread({ runGeneration(g, uids) }, "FullViPER-${g.id}").apply { isDaemon = true; start() }
    }
    private fun runGeneration(g: Generation, uids: Set<Int>) {
        var engine: JamesDspLocalEngine? = null
        try {
            engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
            engine.sampleRate = g.rate.toFloat(); engine.syncWithPreferencesBlocking(true)
            var revision = settingsRevision.get()
            val minimum = AudioTrack.getMinBufferSize(g.rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) throw IOException("Unsupported output PCM")
            val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(g.rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 3840)).setTransferMode(AudioTrack.MODE_STREAM).build()
            synchronized(g) { if (g.closed.get()) { track.release(); return }; g.track = track }
            track.addOnRoutingChangedListener(trackRouteListener, handler)
            track.play()
            g.connecting = true; g.lastWrite = SystemClock.elapsedRealtime()
            var source = openSource(this, 16000, uids, true)
            synchronized(g) { if (g.closed.get()) { source.close(); return }; g.source = source }
            source.begin(16000)
            g.ready = true
            var quietFrames = 0
            var silentInputFrames = 0
            var published = false
            var activeFrames = 0
            var unsuccessfulProbes = 0
            val input = ShortArray(960); val output = ShortArray(960)
            while (!g.closed.get()) {
                val n = source.read(input)
                val capturedAt = SystemClock.elapsedRealtime()
                if (n <= 0 || n > input.size || (!g.monitoring && n % 2 != 0)) throw IOException("Invalid capture transfer=$n")
                if (g.monitoring) {
                    g.lastWrite = SystemClock.elapsedRealtime()
                    source.rendered() // Copy-only monitor never suppresses original playback.
                    val signal = (0 until n).count { kotlin.math.abs(input[it].toInt()) > 8 } > n / 4
                    activeFrames = if (signal) activeFrames + n else 0
                    if (activeFrames >= 1280) {
                        source.close(); g.ready = false; g.lastWrite = SystemClock.elapsedRealtime()
                        source = openSource(this, g.rate, uids, false)
                        synchronized(g) { if (!g.closed.get()) g.source = source }
                        if (g.closed.get()) { source.close(); break }
                        source.begin(g.rate); g.ready = true
                        g.monitoring = false; quietFrames = 0; silentInputFrames = 0; published = false; g.headAt = SystemClock.elapsedRealtime()
                        Timber.i("FULL_ENGINE generation=%d stereo processing activated at %d Hz", g.id, g.rate)
                    }
                    continue
                }
                g.framesIn += n / 2
                val newRevision = settingsRevision.get()
                if (newRevision != revision) { engine.syncWithPreferencesBlocking(true); revision = newRevision }
                beforeProcess?.invoke()
                engine.processInt16(input, output, 0, n)
                if (g.closed.get()) break
                val inputQuiet = (0 until n).all { kotlin.math.abs(input[it].toInt()) <= 2 }
                val outputQuiet = (0 until n).all { kotlin.math.abs(output[it].toInt()) <= 2 }
                silentInputFrames = if (inputQuiet) silentInputFrames + n / 2 else 0
                quietFrames = if (inputQuiet && outputQuiet) quietFrames + n / 2 else 0
                if (quietFrames >= g.rate / 2 || silentInputFrames >= g.rate * 20) {
                    // Quiet is a bypass state, not a failure. A low-fidelity COPY monitor
                    // detects return of source audio; its samples NEVER enter the DSP.
                    source.close(); g.ready = false; g.lastWrite = SystemClock.elapsedRealtime()
                    track.pause(); track.flush(); track.play()
                    source = openSource(this, 16000, uids, true)
                    synchronized(g) { if (!g.closed.get()) g.source = source }
                        if (g.closed.get()) { source.close(); break }
                    source.begin(16000); g.ready = true; g.monitoring = true; activeFrames = 0
                    unsuccessfulProbes++
                    if (unsuccessfulProbes > 2) throw IOException("No confirmed stereo signal after bounded probes; retaining direct playback")
                    handler.post { if (generation === g) publish(CaptureStatus.CHECKING) }
                    continue
                }
                if (!inputQuiet) unsuccessfulProbes = 0
                var offset = 0
                var progressAt = SystemClock.elapsedRealtime()
                while (offset < n && !g.closed.get()) {
                    if (SystemClock.elapsedRealtime() - capturedAt > 1000) throw IOException("Captured block expired before output")
                    val written = track.write(output, offset, n-offset, AudioTrack.WRITE_NON_BLOCKING)
                    if (written < 0 || written % 2 != 0) throw IOException("Output transfer=$written")
                    if (written == 0) {
                        if (SystemClock.elapsedRealtime() - progressAt > 400) throw IOException("Output write stalled")
                        Thread.sleep(2)
                    } else { offset += written; progressAt = SystemClock.elapsedRealtime() }
                }
                if (g.closed.get()) break
                g.framesOut += n / 2; g.lastWrite = SystemClock.elapsedRealtime(); g.ready = true
                source.rendered() // Only successful complete PCM writes renew the routing lease.
                if (!published) { published = true; handler.post { if (generation === g) publish(CaptureStatus.SIGNAL_PROGRESS) } }
            }
        } catch (e: Throwable) {
            if (!g.closed.get()) handler.post { fail(g, "${e.javaClass.simpleName}: ${e.message}") }
        } finally {
            retireResources(g)
            try { engine?.closeAfterProcessing() } catch (e: Exception) { Timber.e(e, "FULL_ENGINE native close failed") }
            workerBusy.set(false)
            handler.post { if (!disposing && generation == null && selected.isNotEmpty() && failures <= 2) scheduleStart(if (failures == 1) 500 else 1500) }
        }
    }
    private fun fail(g: Generation, reason: String) {
        if (generation !== g) return
        failures++
        Timber.e("FULL_ENGINE generation=%d failed=%s inputFrames=%d outputFrames=%d attempt=%d", g.id, reason.take(250), g.framesIn, g.framesOut, failures)
        retire(g); publish(CaptureStatus.DIRECT)
        // Native worker finally schedules at most two retries AFTER releasing routing.
    }
    private fun restart(reason: String, reset: Boolean) {
        if (disposing) return
        Timber.i("FULL_ENGINE: %s", reason)
        retire(generation); if (reset) failures = 0
        scheduleStart(0)
    }
    private fun retire(g: Generation?) { if (g == null) return; retireResources(g); if (generation === g) generation = null }
    private fun retireResources(g: Generation) {
        synchronized(g) {
            if (!g.closed.compareAndSet(false, true)) return
            g.track?.let { track ->
                try { track.removeOnRoutingChangedListener(trackRouteListener) } catch (e: Exception) { Timber.w(e) }
                try { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause(); track.flush() } catch (e: Exception) { Timber.w(e, "FULL_ENGINE output flush failed") }
                try { track.release() } catch (e: Exception) { Timber.e(e, "FULL_ENGINE output release failed") }
            }
            g.track = null
            try { g.source?.close() } catch (e: Exception) { Timber.e(e, "FULL_ENGINE routing release failed; shell lease is the fallback") }
            g.source = null
        }
    }
    private fun publish(status: CaptureStatus) {
        currentCaptureStatus = status
        ServiceNotificationHelper.pushCaptureStatus(this, status)
        sendLocalBroadcast(Intent(CaptureStatus.ACTION_CHANGED).putExtra("status", status.name))
    }
    override fun requestStop() {
        disposing = true
        retire(generation)
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup() else handler.post { cleanup() }
        stopSelf()
    }
    private fun cleanup() {
        if (cleaned) return
        cleaned = true
        handler.removeCallbacksAndMessages(null)
        if (initialized) {
            try { audio.unregisterAudioDeviceCallback(routes) } catch (e: Exception) { Timber.w(e) }
            manager?.destroy(); manager = null
            blockedApps.removeObserver(blockedObserver)
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
            rikka.shizuku.Shizuku.removeBinderDeadListener(binderDead)
            unregisterLocalReceiver(receiver)
        }
        scope.cancel()
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))
    }
    override fun onDestroy() { disposing = true; retire(generation); cleanup(); super.onDestroy() }
    companion object {
        const val MODE_KEY = "full_shell_engine"
        fun requested(context: Context) = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).getBoolean(MODE_KEY, true)
        internal var sourceFactory: ((Context, Int, Set<Int>, Boolean) -> RoutedPcmSource)? = null
        internal var beforeProcess: (() -> Unit)? = null
        internal var authorizationCheck: (() -> Boolean)? = null
        private fun isAuthorized() = authorizationCheck?.invoke() ?: ShellPcmSource.authorized()
        private fun openSource(context: Context, rate: Int, uids: Set<Int>, monitor: Boolean) = sourceFactory?.invoke(context, rate, uids, monitor) ?: ShellPcmSource.open(context, rate, uids, monitor)
    }
}
