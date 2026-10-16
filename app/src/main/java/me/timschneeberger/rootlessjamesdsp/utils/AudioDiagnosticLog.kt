package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.os.Build
import android.util.Log
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import timber.log.Timber
import java.io.File

object AudioDiagnosticLog {
    @Volatile private var store: DiagnosticLogStore? = null
    private fun file(context: Context) = File(context.filesDir, "audio-diagnostics.log")
    fun install(context: Context) {
        val sink = DiagnosticLogStore(file(context))
        store = sink
        sink.append("DIAGNOSTIC_START version=${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} commit=${BuildConfig.COMMIT_SHA} device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}")
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                try { sink.append("$priority ${tag ?: "app"}: $message") }
                catch (error: Exception) { Log.e("AudioDiagnosticLog", "Cannot persist diagnostic", error) }
            }
        })
        Timber.i("DIAGNOSTIC_READY private persistent log installed")
    }
    fun snapshot(context: Context): String = try {
        (store ?: DiagnosticLogStore(file(context))).snapshot().ifEmpty {
            "DIAGNOSTIC_ERROR: log is empty; initialization failed\n"
        }
    } catch (error: Exception) { "DIAGNOSTIC_ERROR: ${error.javaClass.name}: ${error.message}\n" }
}
