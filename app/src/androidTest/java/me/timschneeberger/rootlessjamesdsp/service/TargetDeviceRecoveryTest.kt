package me.timschneeberger.rootlessjamesdsp.service

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.utils.AudioDiagnosticLog
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in only: owner starts actual playback/capture before running this test.
 * API cleanup assertions are separate from the required independent audible trace.
 */
class TargetDeviceRecoveryTest {
    @Test fun deliberateWorkerFailureReleasesSourceBeforeRetry() {
        assumeTrue("Requires live owner-authorized playback",
            InstrumentationRegistry.getArguments().getString("allowPlaybackFault") == "yes")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        var service: RootlessAudioProcessorService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as BaseAudioProcessorService.LocalBinder).service as RootlessAudioProcessorService
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        var bound = false
        instrumentation.runOnMainSync {
            // No AUTO_CREATE: never creates a capture service without a grant.
            bound = context.bindService(Intent(context, RootlessAudioProcessorService::class.java), connection, 0)
        }
        try {
            assertTrue("Start DSP with Spotify first", bound && connected.await(5, TimeUnit.SECONDS))
            val before = AudioDiagnosticLog.snapshot(context)
            val releasesBefore = before.split("MUTE_RELEASED").size
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val inject = service!!.javaClass.declaredMethods.single {
                it.name.startsWith("requestProcessingFailureForTest")
            }.apply { isAccessible = true }
            inject.invoke(service)
            var after = before
            while (android.os.SystemClock.elapsedRealtime() - startedAt < 4500L) {
                after = AudioDiagnosticLog.snapshot(context)
                if (after.split("MUTE_RELEASED").size > releasesBefore) break
                Thread.sleep(10)
            }
            assertTrue("A real mute effect must be released", after.split("MUTE_RELEASED").size > releasesBefore)
            assertTrue(after.contains("Audio fail-open"))
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            android.util.Log.i("TargetDeviceRecoveryTest", "API release observed after ${elapsed}ms; independently measure audible restoration")
        } finally {
            if (bound) instrumentation.runOnMainSync { context.unbindService(connection) }
        }
    }
}
