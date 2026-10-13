package me.timschneeberger.rootlessjamesdsp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.flavor.CrashlyticsImpl
import me.timschneeberger.rootlessjamesdsp.interop.JamesDspLocalEngine
import me.timschneeberger.rootlessjamesdsp.interop.ProcessorMessageHandler
import me.timschneeberger.rootlessjamesdsp.model.IEffectSession
import me.timschneeberger.rootlessjamesdsp.model.preference.AudioEncoding
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistDatabase
import me.timschneeberger.rootlessjamesdsp.model.room.AppBlocklistRepository
import me.timschneeberger.rootlessjamesdsp.model.room.BlockedApp
import me.timschneeberger.rootlessjamesdsp.model.rootless.SessionRecordingPolicyEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.OnRootlessSessionChangeListener
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.session.rootless.SessionRecordingPolicyManager
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_PREFERENCES_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SAMPLE_RATE_UPDATED
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_HARD_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_RELOAD_LIVEPROG
import me.timschneeberger.rootlessjamesdsp.utils.Constants.ACTION_SERVICE_SOFT_REBOOT_CORE
import me.timschneeberger.rootlessjamesdsp.utils.extensions.CompatExtensions.getParcelableAs
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.PermissionExtensions.hasRecordPermission
import me.timschneeberger.rootlessjamesdsp.utils.notifications.Notifications
import me.timschneeberger.rootlessjamesdsp.utils.notifications.ServiceNotificationHelper
import me.timschneeberger.rootlessjamesdsp.utils.preferences.Preferences
import me.timschneeberger.rootlessjamesdsp.utils.sdkAbove
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean


@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : BaseAudioProcessorService() {
    // System services
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager

    // Media projection token
    private var mediaProjection: MediaProjection? = null
    private var mediaProjectionStartIntent: Intent? = null

    // Processing
    @Volatile private var recreateRecorderRequested = false
    private var recorderThread: Thread? = null
    private lateinit var engine: JamesDspLocalEngine
    private val isRunning: Boolean
        get() = recorderThread != null

    // Session management
    private lateinit var sessionManager: RootlessSessionManager
    private var sessionLossRetryCount = 0
    @Volatile
    private var notificationSessions: Array<IEffectSession> = emptyArray()

    // Idle detection
    private var isProcessorIdle = false
    private var suspendOnIdle = false

    // Exclude restricted apps flag
    private var excludeRestrictedSessions = false

    // Whether the app list names what to process rather than what to skip
    private var allowlistMode = false

    // Termination flags
    @Volatile private var isProcessorDisposing = false
    @Volatile private var isServiceDisposing = false
    @Volatile private var activeRecorder: AudioRecord? = null
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var lastProgressMs = 0L
    private val engineClosed = AtomicBoolean(false)
    private fun closeEngineOnce() {
        if (engineClosed.compareAndSet(false, true)) engine.close()
    }
    private val recoveryHandler = Handler(Looper.getMainLooper())
    private val watchdog = object : Runnable {
        override fun run() {
            if (isServiceDisposing || isProcessorDisposing || recorderThread == null) return
            if (SystemClock.elapsedRealtime() - lastProgressMs > 5000L) {
                Timber.e("AUDIO_RECOVERY stalled audio I/O; stopping service and releasing mute effects")
                stopSelf()
                return
            }
            recoveryHandler.postDelayed(this, 1000L)
        }
    }

    // Shared preferences
    private val preferences: Preferences.App by inject()
    private val preferencesVar: Preferences.Var by inject()

    // Room databases
    private val applicationScope = CoroutineScope(SupervisorJob())
    private val blockedAppDatabase by lazy { AppBlocklistDatabase.getDatabase(this, applicationScope) }
    private val blockedAppRepository by lazy { AppBlocklistRepository(blockedAppDatabase.appBlocklistDao()) }
    private val blockedApps by lazy { blockedAppRepository.blocklist.asLiveData() }
    private val blockedAppObserver = Observer<List<BlockedApp>?> {
        Timber.d("blockedAppObserver: Database changed; ignored=${!isRunning}")
        if(isRunning)
            recreateRecorderRequested = true
    }

    override fun onCreate() {
        super.onCreate()

        // Get reference to system services
        audioManager = getSystemService<AudioManager>()!!
        mediaProjectionManager = getSystemService<MediaProjectionManager>()!!
        notificationManager = getSystemService<NotificationManager>()!!

        // Setup session manager
        sessionManager = RootlessSessionManager(this)
        sessionManager.sessionDatabase.setOnSessionLossListener(onSessionLossListener)
        sessionManager.sessionDatabase.setOnAppProblemListener(onAppProblemListener)
        sessionManager.sessionDatabase.registerOnSessionChangeListener(onSessionChangeListener)
        sessionManager.sessionPolicyDatabase.registerOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)

        // Setup core engine
        engine = JamesDspLocalEngine(this, ProcessorMessageHandler())
        engine.syncWithPreferences()

        // Setup general-purpose broadcast receiver
        val filter = IntentFilter()
        filter.addAction(ACTION_PREFERENCES_UPDATED)
        filter.addAction(ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(ACTION_SERVICE_SOFT_REBOOT_CORE)
        registerLocalReceiver(broadcastReceiver, filter)

        // Setup shared preferences
        preferences.registerOnSharedPreferenceChangeListener(preferencesListener)
        loadFromPreferences(getString(R.string.key_powersave_suspend))
        loadFromPreferences(getString(R.string.key_session_exclude_restricted))
        loadFromPreferences(getString(R.string.key_blocklist_allowlist_mode))

        // Setup database observer
        blockedApps.observeForever(blockedAppObserver)

        notificationManager.cancel(Notifications.ID_SERVICE_STARTUP)

        // No need to recreate in this stage
        recreateRecorderRequested = false

        // Launch foreground service
        startForeground(
            Notifications.ID_SERVICE_STATUS,
            ServiceNotificationHelper.createServiceNotification(this, arrayOf()),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        Timber.d("onStartCommand")

        // Handle intent action
        when (intent.action) {
            null -> {
                Timber.wtf("onStartCommand: intent.action is null")
            }
            ACTION_START -> {
                Timber.d("Starting service")
            }
            ACTION_STOP -> {
                Timber.d("Stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) {
            return START_NOT_STICKY
        }

        // Cancel outdated notifications
        notificationManager.cancel(Notifications.ID_SERVICE_SESSION_LOSS)
        notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)

        // Setup media projection
        mediaProjectionStartIntent = intent.extras?.getParcelableAs(EXTRA_MEDIA_PROJECTION_DATA)

        mediaProjection = try {
            mediaProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                mediaProjectionStartIntent!!
            )
        }
        catch (ex: Exception) {
            Timber.e("Failed to acquire media projection")
            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))
            Timber.e(ex)
            null
        }

        mediaProjection?.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        if (mediaProjection != null) {
            startRecording()
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        } else {
            Timber.w("Failed to capture audio")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isServiceDisposing = true

        recoveryHandler.removeCallbacks(watchdog)
        // Prevent callbacks from re-enabling mute while restoring ordinary audio.
        sessionManager.sessionDatabase.destroy()

        // Release the projection FIRST. If anything below fails, the capture
        // must still be handed back or every app on the device stays silent
        // until this process is killed.
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        catch (ex: Exception) { Timber.e(ex, "Failed to release media projection") }
        finally { mediaProjection = null }

        // Stop recording and release engine
        try { stopRecording() }
        catch (ex: Exception) { Timber.e(ex, "Failed to stop recording") }
        try { if (recorderThread?.isAlive != true) closeEngineOnce() }
        catch (ex: Throwable) { Timber.e(ex, "Failed to close engine") }

        // Stop foreground service
        try { stopForeground(STOP_FOREGROUND_REMOVE) }
        catch (ex: Exception) { Timber.e(ex) }

        // Notify app about service termination
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))

        // Unregister database observer
        blockedApps.removeObserver(blockedAppObserver)

        // Unregister receivers and release resources
        unregisterLocalReceiver(broadcastReceiver)

        sessionManager.sessionPolicyDatabase.unregisterOnRestrictedSessionChangeListener(onSessionPolicyChangeListener)
        sessionManager.sessionDatabase.unregisterOnSessionChangeListener(onSessionChangeListener)
        sessionManager.destroy()

        preferences.unregisterOnSharedPreferenceChangeListener(preferencesListener)
        notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

        stopSelf()
        super.onDestroy()
    }

    // Preferences listener
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, key ->
        loadFromPreferences(key)
    }

    // Projection termination callback
    private val projectionCallback = object: MediaProjection.Callback() {
        override fun onStop() {
            if(isServiceDisposing) {
                // Planned shutdown
                return
            }

            Timber.w("Capture permission revoked. Stopping service.")

            sendLocalBroadcast(Intent(Constants.ACTION_DISCARD_AUTHORIZATION))

            this@RootlessAudioProcessorService.toast(getString(R.string.capture_permission_revoked_toast))

            notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
            stopSelf()
        }
    }

    // General purpose broadcast receiver
    private val broadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SAMPLE_RATE_UPDATED -> engine.syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                ACTION_PREFERENCES_UPDATED -> engine.syncWithPreferences()
                ACTION_SERVICE_RELOAD_LIVEPROG -> engine.syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG, Constants.PREF_LIVEPROG2, Constants.PREF_LIVEPROG3, Constants.PREF_LIVEPROG4))
                ACTION_SERVICE_HARD_REBOOT_CORE -> restartRecording()
                ACTION_SERVICE_SOFT_REBOOT_CORE -> requestAudioRecordRecreation()
            }
        }
    }

    // Session loss listener
    private val onSessionLossListener = object: RootlessSessionDatabase.OnSessionLossListener {
        override fun onSessionLost(sid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_loss_ignore)) {
                // Check if retry count exceeded
                if(sessionLossRetryCount < SESSION_LOSS_MAX_RETRIES) {
                    // Retry
                    sessionLossRetryCount++
                    Timber.d("Session lost. Retry count: $sessionLossRetryCount/$SESSION_LOSS_MAX_RETRIES")
                    sessionManager.pollOnce(false)
                    restartRecording()
                    return
                }
                else {
                    sessionLossRetryCount = 0
                    Timber.d("Giving up on saving session. User interaction required.")
                }

                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)
                ServiceNotificationHelper.pushSessionLossNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent)
                this@RootlessAudioProcessorService.toast(getString(R.string.session_control_loss_toast), false)
                Timber.w("Terminating service due to session loss")
                stopSelf()
            }
        }
    }

    // Session change listener
    private val onSessionChangeListener = object : OnRootlessSessionChangeListener {
        override fun onSessionChanged(sessionList: HashMap<Int, IEffectSession>) {
            isProcessorIdle = sessionList.size == 0
            Timber.d("onSessionChanged: isProcessorIdle=$isProcessorIdle")

            notificationSessions = sessionList.values.toTypedArray()

            ServiceNotificationHelper.pushServiceNotification(
                this@RootlessAudioProcessorService,
                notificationSessions
            )
        }
    }

    // App problem listener
    private val onAppProblemListener = object : RootlessSessionDatabase.OnAppProblemListener {
        override fun onAppProblemDetected(uid: Int) {
            // Push notification if enabled
            if(!preferences.get<Boolean>(R.string.key_session_app_problem_ignore)) {
                // Request users attention
                notificationManager.cancel(Notifications.ID_SERVICE_STATUS)

                // Determine if we should redirect instantly, or push a non-intrusive notification
                if(preferencesVar.get<Boolean>(R.string.key_is_activity_active) ||
                    preferencesVar.get<Boolean>(R.string.key_is_app_compat_activity_active)) {
                    startActivity(
                        ServiceNotificationHelper.createAppTroubleshootIntent(
                            this@RootlessAudioProcessorService,
                            mediaProjectionStartIntent,
                            uid,
                            directLaunch = true
                        )
                    )
                    notificationManager.cancel(Notifications.ID_SERVICE_APPCOMPAT)
                }
                else
                    ServiceNotificationHelper.pushAppIssueNotification(this@RootlessAudioProcessorService, mediaProjectionStartIntent, uid)

                this@RootlessAudioProcessorService.toast(getString(R.string.session_app_compat_toast), false)
                Timber.w("Terminating service due to app incompatibility; redirect user to troubleshooting options")
                stopSelf()
            }
        }
    }

    // Session policy change listener
    private val onSessionPolicyChangeListener = object : SessionRecordingPolicyManager.OnSessionRecordingPolicyChangeListener {
        override fun onSessionRecordingPolicyChanged(sessionList: HashMap<String, SessionRecordingPolicyEntry>, isMinorUpdate: Boolean) {
            if(!this@RootlessAudioProcessorService.excludeRestrictedSessions) {
                Timber.d("onRestrictedSessionChanged: blocked; excludeRestrictedSessions disabled")
                return
            }

            if(!isMinorUpdate) {
                Timber.d("onRestrictedSessionChanged: major update detected; requesting soft-reboot")
                requestAudioRecordRecreation()
            }
            else {
                Timber.d("onRestrictedSessionChanged: minor update detected")
            }
        }
    }

    private fun loadFromPreferences(key: String?){
        when (key) {
            getString(R.string.key_powersave_suspend) -> {
                suspendOnIdle = preferences.get<Boolean>(R.string.key_powersave_suspend)
                Timber.d("Suspend on idle set to $suspendOnIdle")
            }
            getString(R.string.key_session_exclude_restricted) -> {
                excludeRestrictedSessions = preferences.get<Boolean>(R.string.key_session_exclude_restricted)
                Timber.d("Exclude restricted set to $excludeRestrictedSessions")

                requestAudioRecordRecreation()
            }
            getString(R.string.key_blocklist_allowlist_mode) -> {
                allowlistMode = preferences.get<Boolean>(R.string.key_blocklist_allowlist_mode)
                Timber.d("App list allowlist mode set to $allowlistMode")

                // Android bakes the matching rules into the AudioRecord when it
                // is built, so flipping this changes nothing until it is rebuilt.
                requestAudioRecordRecreation()
            }
        }
    }

    // Request recreation of the AudioRecord object to update AudioPlaybackRecordingConfiguration
    fun requestAudioRecordRecreation() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("recreateAudioRecorder: service or processor already disposing")
            return
        }

        recreateRecorderRequested = true
    }

    // Start recording thread
    @SuppressLint("BinaryOperationInTimber")
    private fun startRecording() {
        // Sanity check
        if (!hasRecordPermission()) {
            Timber.e("Record audio permission missing. Can't record")
            stopSelf()
            return
        }

        // Load preferences
        val encoding = AudioEncoding.fromInt(
            preferences.get<String>(R.string.key_audioformat_encoding).toIntOrNull() ?: 1
        )
        val requestedSamples = preferences.get<Float>(R.string.key_audioformat_buffersize).toInt()
        val bufferSize = requestedSamples.coerceAtLeast(2).let { it + it % 2 }
        val bufferSizeBytes = when (encoding) {
            AudioEncoding.PcmFloat -> bufferSize * Float.SIZE_BYTES
            else -> bufferSize * Short.SIZE_BYTES
        }
        val encodingFormat = when (encoding) {
            AudioEncoding.PcmShort -> AudioFormat.ENCODING_PCM_16BIT
            else -> AudioFormat.ENCODING_PCM_FLOAT
        }
        val sampleRate = clamp(determineSamplingRate(), 44100, 48000)

        Timber.i("Sample rate: $sampleRate; Encoding: ${encoding.name}; " +
                "Buffer size: $bufferSize; Buffer size (bytes): $bufferSizeBytes ; " +
                "HAL buffer size (bytes): ${determineBufferSize()}")

        // Create recorder and track
        var recorder: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            recorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
            track = buildAudioTrack(encodingFormat, sampleRate, bufferSizeBytes)
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord uninitialized" }
            check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack uninitialized" }
        }
        catch(ex: Exception) {
            recorder?.release()
            track?.release()
            Timber.e("Failed to create initial audio record/track")
            Timber.e(ex)
            stopSelf()
            return
        }

        var currentRecorder = checkNotNull(recorder)
        val currentTrack = checkNotNull(track)
        activeRecorder = currentRecorder
        activeTrack = currentTrack

        if(engine.sampleRate.toInt() != sampleRate) {
            Timber.d("Sampling rate changed to ${sampleRate}Hz")
            engine.sampleRate = sampleRate.toFloat()
        }

        lastProgressMs = SystemClock.elapsedRealtime()
        recorderThread = Thread {
            var meaningfulOutputMissingSecs = 0.0
            var captureSilenceSecs = 0.0
            var nextLogMs = 0L
            try {
                val floatBuffer = FloatArray(bufferSize)
                val floatOutBuffer = FloatArray(bufferSize)
                val shortBuffer = ShortArray(bufferSize)
                val shortOutBuffer = ShortArray(bufferSize)
                while (!isProcessorDisposing) {
                    lastProgressMs = SystemClock.elapsedRealtime()
                    if (recreateRecorderRequested) {
                        recreateRecorderRequested = false
                        currentRecorder.stop()
                        currentTrack.pause()
                        currentTrack.flush()
                        currentRecorder.release()
                        check(mediaProjection != null) { "Projection lost during recorder recreation" }
                        currentRecorder = buildAudioRecord(encodingFormat, sampleRate, bufferSizeBytes)
                        activeRecorder = currentRecorder
                        check(currentRecorder.state == AudioRecord.STATE_INITIALIZED)
                    }
                    if (isProcessorIdle && suspendOnIdle) {
                        if (currentRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) currentRecorder.stop()
                        if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING) currentTrack.pause()
                        Thread.sleep(50)
                        continue
                    }
                    if (currentRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) currentRecorder.startRecording()
                    check(currentRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING) currentTrack.play()
                    check(currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING)
                    val count = if (encoding == AudioEncoding.PcmShort)
                        currentRecorder.read(shortBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
                    else currentRecorder.read(floatBuffer, 0, bufferSize, AudioRecord.READ_BLOCKING)
                    if (isProcessorDisposing) break
                    AudioIoGuard.validateRead(count, bufferSize)
                    if (count == 0) { Thread.sleep(5); continue }
                    val input = if (encoding == AudioEncoding.PcmShort) AudioIoGuard.measure(shortBuffer, count)
                        else AudioIoGuard.measure(floatBuffer, count)
                    val output = if (encoding == AudioEncoding.PcmShort) {
                        engine.processInt16(shortBuffer, shortOutBuffer, 0, count)
                        AudioIoGuard.measure(shortOutBuffer, count)
                    } else {
                        engine.processFloat(floatBuffer, floatOutBuffer, 0, count)
                        AudioIoGuard.measure(floatOutBuffer, count)
                    }
                    val seconds = count / (2.0 * sampleRate)
                    meaningfulOutputMissingSecs = if (input.peak > 0.0001 && output.peak < 0.0000001)
                        meaningfulOutputMissingSecs + seconds else 0.0
                    captureSilenceSecs = if (notificationSessions.isNotEmpty() && input.peak < 0.0000001)
                        captureSilenceSecs + seconds else 0.0
                    check(meaningfulOutputMissingSecs < 3.0) { "DSP output silent with meaningful input" }
                    // Conservative recovery: silence with muted sessions cannot prove source playback
                    // is active, but must never leave the user's ordinary audio muted indefinitely.
                    check(captureSilenceSecs < 8.0) { "Capture silent while source sessions are muted; compatibility unconfirmed" }
                    AudioIoGuard.writeFully(count, { isProcessorDisposing }) { offset, remaining ->
                        if (encoding == AudioEncoding.PcmShort)
                            currentTrack.write(shortOutBuffer, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                        else currentTrack.write(floatOutBuffer, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                    }
                    val now = SystemClock.elapsedRealtime()
                    lastProgressMs = now
                    if (now >= nextLogMs) {
                        Timber.i("AUDIO_RECOVERY read=%d inputRms=%f inputPeak=%f outputRms=%f outputPeak=%f frames=%d underruns=%d route=%s rate=%d encoding=%s",
                            count, input.rms, input.peak, output.rms, output.peak,
                            currentTrack.playbackHeadPosition, currentTrack.underrunCount,
                            currentTrack.routedDevice?.type, sampleRate, encoding.name)
                        nextLogMs = now + 1000L
                    }
                }
            } catch (e: InterruptedException) {
                if (!isProcessorDisposing) Timber.e(e, "AUDIO_RECOVERY unexpected interruption")
            } catch (e: Throwable) {
                Timber.e(e, "AUDIO_RECOVERY pipeline failure; restoring ordinary audio")
            } finally {
                runCatching { currentRecorder.stop() }
                runCatching { currentTrack.stop() }
                runCatching { currentRecorder.release() }
                runCatching { currentTrack.release() }
                activeRecorder = null
                activeTrack = null
                if (isServiceDisposing) closeEngineOnce()
                else if (!isProcessorDisposing) recoveryHandler.post { stopSelf() }
            }
        }
        recorderThread!!.start()
        recoveryHandler.removeCallbacks(watchdog)
        recoveryHandler.postDelayed(watchdog, 1000L)
    }

    // Terminate recording thread
    fun stopRecording() {
        if (recorderThread != null) {
            isProcessorDisposing = true
            recoveryHandler.removeCallbacks(watchdog)
            runCatching { activeRecorder?.stop() }
            runCatching { activeTrack?.pause() }
            recorderThread!!.interrupt()
            recorderThread!!.join(500)
            if (recorderThread!!.isAlive) {
                Timber.e("AUDIO_RECOVERY worker did not terminate; refusing restart")
                stopSelf()
                return
            }
            recorderThread = null
        }
    }

    // Hard restart recording thread
    fun restartRecording() {
        if(isProcessorDisposing || isServiceDisposing) {
            Timber.e("restartRecording: service or processor already disposing")
            return
        }

        stopRecording()
        if (recorderThread?.isAlive == true) return
        isProcessorDisposing = false
        recreateRecorderRequested = false
        startRecording()
    }

    private fun buildAudioTrack(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioTrack {
        val attributesBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setFlags(0)

        sdkAbove(Build.VERSION_CODES.Q) {
            attributesBuilder.setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        }

        val format = AudioFormat.Builder()
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .build()

        val frameSizeInBytes: Int = if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            2 /* channels */ * 2 /* bytes */
        } else {
            2 /* channels */ * 4 /* bytes */
        }

        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding)
        check(minBytes > 0) { "Unsupported output format: $minBytes" }
        val bufferSize = AudioIoGuard.alignBytes(maxOf(bufferSizeBytes, minBytes), frameSizeInBytes)

        Timber.d("Using buffer size $bufferSize")

        return AudioTrack.Builder()
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setAudioAttributes(attributesBuilder.build())
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(encoding: Int, sampleRate: Int, bufferSizeBytes: Int): AudioRecord {
        if (!hasRecordPermission()) {
            Timber.e("buildAudioRecord: RECORD_AUDIO not granted")
            throw RuntimeException("RECORD_AUDIO not granted")
        }

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val configBuilder = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)

        // Read the app list the other way round when asked to: capture only the
        // apps on it rather than everything except them. Android will not let
        // us do both at once, so this is one branch or the other, never a mix.
        //
        // An empty list in this mode is treated as "not configured yet" rather
        // than "capture nothing": someone who has just turned the mode on and
        // picked no apps would otherwise get silence and reasonably conclude
        // the app was broken.
        // Our own uid is dropped rather than trusted not to be on the list.
        // In the ordinary path it is excluded explicitly; here there is no
        // exclusion to hide behind, and capturing ourselves is a loop.
        val allowed = if (allowlistMode)
            blockedApps.value?.map { it.uid }.orEmpty().filter { it != Process.myUid() }
        else emptyList()
        if (allowed.isNotEmpty()) {
            allowed.forEach { configBuilder.addMatchingUid(it) }
            sessionManager.sessionDatabase.setAllowedUids(allowed.toTypedArray())
            sessionManager.sessionDatabase.setExcludedUids(arrayOf())
            sessionManager.pollOnce(false)
            Timber.d("buildAudioRecord: Allowed UIDs only: ${allowed.joinToString("; ")}")
        }
        else {
            val excluded = (if(excludeRestrictedSessions)
                sessionManager.sessionPolicyDatabase.getRestrictedUids().toList()
            else {
                sessionManager.pollOnce(false)
                emptyList()
            }).toMutableList()

            if (!allowlistMode)
                blockedApps.value?.map { it.uid }?.let {
                    excluded += it
                }
            excluded += Process.myUid()

            excluded.forEach { configBuilder.excludeUid(it) }
            sessionManager.sessionDatabase.setAllowedUids(null)
            sessionManager.sessionDatabase.setExcludedUids(excluded.toTypedArray())
            sessionManager.pollOnce(false)

            Timber.d("buildAudioRecord: Excluded UIDs: ${excluded.joinToString("; ")}")
        }

        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(AudioIoGuard.alignBytes(maxOf(bufferSizeBytes,
                AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO, encoding).also {
                    check(it > 0) { "Unsupported capture format: $it" }
                }), if (encoding == AudioFormat.ENCODING_PCM_16BIT) 4 else 8))
            .setAudioPlaybackCaptureConfig(configBuilder.build())
            .build()
    }

    // Determine HAL sampling rate
    private fun determineSamplingRate(): Int {
        val sampleRateStr: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val srate = sampleRateStr?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 48000
        Timber.i("Real HAL sampling rate is $srate")
        return srate
    }

    // Determine HAL buffer size
    private fun determineBufferSize(): Int {
        val framesPerBuffer: String? = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        return framesPerBuffer?.let { str -> Integer.parseInt(str).takeUnless { it == 0 } } ?: 256
    }

    companion object {
        const val SESSION_LOSS_MAX_RETRIES = 1

        const val ACTION_START = BuildConfig.APPLICATION_ID + ".rootless.service.START"
        const val ACTION_STOP = BuildConfig.APPLICATION_ID + ".rootless.service.STOP"
        const val EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData"
        const val EXTRA_APP_UID = "uid"
        const val EXTRA_APP_COMPAT_INTERNAL_CALL = "appCompatInternalCall"

        fun start(context: Context, data: Intent?) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStartIntent(context, data))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }

        fun stop(context: Context) {
            try {
                context.startForegroundService(ServiceNotificationHelper.createStopIntent(context))
            }
            catch(ex: Exception) {
                CrashlyticsImpl.recordException(ex)
            }
        }
    }
}
