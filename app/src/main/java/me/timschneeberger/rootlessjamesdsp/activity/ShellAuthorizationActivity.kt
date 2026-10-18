package me.timschneeberger.rootlessjamesdsp.activity

import android.content.Intent
import android.os.Bundle
import android.content.pm.PackageManager
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast
import rikka.shizuku.Shizuku
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast

/** Uses Shizuku's normal user-consent UI; never changes Android security settings. */
class ShellAuthorizationActivity : BaseActivity() {
    override val disableAppTheme = true
    private val result = Shizuku.OnRequestPermissionResultListener { request, grant ->
        if (request == REQUEST) runOnUiThread {
            if (grant == PackageManager.PERMISSION_GRANTED) launchEngine()
            else { toast("Shizuku authorization was not granted. Original audio remains direct.", true); finishWithoutEngine() }
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Shizuku.addRequestPermissionResultListener(result)
        try {
            if (!Shizuku.pingBinder()) {
                toast("Start Shizuku with wireless or USB debugging, then enable ViPER again. Root is not required.", true)
                finishWithoutEngine(); return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) launchEngine()
            else Shizuku.requestPermission(REQUEST)
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Full-engine authorization failed")
            toast("Shizuku is unavailable. Original audio remains direct.", true); finishWithoutEngine()
        }
    }
    private fun launchEngine() {
        if (Shizuku.getUid() != 2000) { toast("Use Shizuku in ADB mode for this rootless backend.", true); finishWithoutEngine() }
        else { RootlessAudioProcessorService.start(this, null); finish() }
    }
    private fun finishWithoutEngine() { sendLocalBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED)); finish() }
    override fun onDestroy() { Shizuku.removeRequestPermissionResultListener(result); super.onDestroy() }
    companion object { private const val REQUEST = 421 }
}
