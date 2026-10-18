package me.timschneeberger.rootlessjamesdsp.service

import android.content.*
import android.content.pm.ServiceInfo
import android.os.Process
import androidx.lifecycle.Observer
import androidx.lifecycle.asLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.interop.PreferenceCache
import me.timschneeberger.rootlessjamesdsp.model.room.*
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionManager
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.utils.*
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import me.timschneeberger.rootlessjamesdsp.utils.notifications.*
import timber.log.Timber

/** Exclusive compatibility backend: public Android EQ/gain on original sessions.
 * No projection, recorder, replacement track, muter or native ViPER engine. */
class DirectAudioProcessorService : BaseAudioProcessorService() {
    private var disposing = false
    private var initialized = false
    private val scope = CoroutineScope(SupervisorJob())
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val registry = DirectEffectRegistry<DirectEffectSettings>(
        { sid, config, lost -> DirectAudioEffect(sid, config) { lost(); publishStatus() } },
        { Timber.w("DIRECT_COMPAT: %s", it) })
    private val health = object : Runnable {
        override fun run() {
            if (disposing || !initialized) return
            registry.checkHealth(); publishStatus()
            handler.postDelayed(this, 1000)
        }
    }
    private var sessions: ISessionInfoDump? = null
    private var manager: BaseSessionManager? = null
    private val appPrefs by lazy { getSharedPreferences(Constants.PREF_APP, MODE_PRIVATE) }
    private val blockedApps by lazy { AppBlocklistRepository(AppBlocklistDatabase.getDatabase(this, scope).appBlocklistDao()).blocklist.asLiveData() }
    private var blocked = emptySet<Int>()
    private val blockedObserver = Observer<List<BlockedApp>?> { list -> blocked = list.orEmpty().map { it.uid }.toSet(); reconcile() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MODE_KEY && !enabled(this)) stopSelf() else reconcile()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!disposing) try { registry.update(settings()); publishStatus() }
            catch (e: Exception) { Timber.e(e, "DIRECT_COMPAT invalid settings; disabling effects"); stopSelf() }
        }
    }
    var currentCaptureStatus = CaptureStatus.CHECKING
        private set
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_SERVICE_STATUS, ServiceNotificationHelper.createServiceNotification(this, arrayOf()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (activeServices > 1 || !enabled(this)) { stopSelf(); return }
        initialized = true
        appPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerLocalReceiver(receiver, IntentFilter(Constants.ACTION_PREFERENCES_UPDATED))
        blockedApps.observeForever(blockedObserver)
        manager = object : BaseSessionManager(this) {
            override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
                if (!disposing && sessionDump != null) { sessions = sessionDump; reconcile() }
            }
        }
        manager?.pollOnce(false)
        sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STARTED))
        publishStatus()
        handler.postDelayed(health, 1000)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == RootlessAudioProcessorService.ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }
    private fun settings() = DirectEffectSettings.parse(
        PreferenceCache.uncachedGet(this, Constants.PREF_EQ, R.string.key_eq_enable, false),
        PreferenceCache.uncachedGet(this, Constants.PREF_EQ, R.string.key_eq_bands, ""),
        PreferenceCache.uncachedGet(this, Constants.PREF_OUTPUT, R.string.key_output_postgain, 0f))
    private fun reconcile() {
        if (disposing || !initialized) return
        try {
            val allowlist = appPrefs.getBoolean(getString(R.string.key_blocklist_allowlist_mode), false)
            val accepted = sessions?.sessions?.filter { (sid, data) ->
                sid > 0 && data.uid != Process.myUid() && data.packageName != packageName && data.isUsageRecordable() &&
                    (if (allowlist && blocked.isNotEmpty()) data.uid in blocked else allowlist || data.uid !in blocked)
            }?.mapValues { it.value.uid }.orEmpty()
            registry.reconcile(accepted, settings())
            publishStatus()
        } catch (e: Exception) { Timber.e(e, "DIRECT_COMPAT failed; releasing direct effects"); stopSelf() }
    }
    private fun publishStatus() {
        if (disposing || !initialized) return
        val next = if (registry.controlledCount() > 0) CaptureStatus.DIRECT_EFFECTS else CaptureStatus.DIRECT_WAITING
        if (next == currentCaptureStatus) return
        currentCaptureStatus = next
        Timber.i("DIRECT_COMPAT status=%s controlled=%d", next, registry.controlledCount())
        ServiceNotificationHelper.pushCaptureStatus(this, currentCaptureStatus)
        sendLocalBroadcast(Intent(CaptureStatus.ACTION_CHANGED).putExtra("status", currentCaptureStatus.name))
    }
    override fun onDestroy() {
        disposing = true
        registry.close()
        if (initialized) {
            fun cleanup(name: String, action: () -> Unit) {
                try { action() } catch (e: Exception) { Timber.e(e, "DIRECT_COMPAT cleanup failed: %s", name) }
            }
            cleanup("sessions") { manager?.destroy(); manager = null }
            cleanup("app filter") { blockedApps.removeObserver(blockedObserver) }
            cleanup("preferences") { appPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
            cleanup("receiver") { unregisterLocalReceiver(receiver) }
            sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))
        }
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }
    companion object {
        const val MODE_KEY = "direct_session_compatibility"
        fun enabled(context: Context) = context.getSharedPreferences(Constants.PREF_APP, Context.MODE_PRIVATE).getBoolean(MODE_KEY, true)
        fun selectedService(context: Context): Class<out BaseAudioProcessorService> =
            if (enabled(context)) DirectAudioProcessorService::class.java else RootlessAudioProcessorService::class.java
    }
}
