package me.timschneeberger.rootlessjamesdsp.interop

import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.DataInputStream

@RunWith(AndroidJUnit4::class)
class ShellRoutingGuardTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private fun shell(command:String) {
        inst.uiAutomation.executeShellCommand(command).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { s->s.copyTo(java.io.ByteArrayOutputStream()) } }
    }
    private fun rms(m:Visualizer):Int {
        SystemClock.sleep(1000)
        return List(8) { val v=Visualizer.MeasurementPeakRms(); assertEquals(0,m.getMeasurementPeakRms(v)); SystemClock.sleep(60); v.mRms }.sorted()[4]
    }
    @Test fun stuckCaptureCreationReleasesRealRegisteredPolicyOnHardDeadline()=exercise("init")
    @Test fun stuckPolicyCleanupCannotPreventRealBinderDeathRelease()=exercise("cleanup")
    private fun exercise(mode:String) {
        var process:ShizukuRemoteProcess?=null
        var meter:Visualizer?=null
        try {
            assertTrue(Shizuku.pingBinder()); assertEquals(0,Shizuku.checkSelfPermission()); assertEquals(2000,Shizuku.getUid())
            shell("am start -W -n org.rv4a.captureprobe/.DirectToneActivity")
            meter=Visualizer(0).apply { setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS);enabled=true }
            val baseline=rms(meter);assertTrue("Source absent: $baseline",baseline> -7000)
            val uid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe",0).uid
            val script="export CLASSPATH=\"${'$'}1\"; shift; exec /system/bin/app_process /system/bin \"${'$'}@\""
            val paths=context.applicationInfo.sourceDir+":"+inst.context.applicationInfo.sourceDir
            val command=arrayOf("/system/bin/sh","-c",script,"rv4a-guard-test",paths,
                "me.timschneeberger.rootlessjamesdsp.audio.ShellGuardFaultWorker",mode,"48000",uid.toString())
            val method=Shizuku::class.java.getDeclaredMethod("newProcess",Array<String>::class.java,Array<String>::class.java,String::class.java).apply {isAccessible=true}
            val worker=method.invoke(null,command,null,null) as ShizukuRemoteProcess
            process=worker
            val diagnostics=StringBuffer()
            val stderr=Thread {try { worker.errorStream.bufferedReader().useLines { lines->lines.forEach { diagnostics.append(it).append('\n') } } } catch (_:java.io.IOException) {} }.apply {isDaemon=true;start()}
            val started=SystemClock.elapsedRealtime()
            if(mode=="cleanup") {
                val data=DataInputStream(worker.inputStream)
                assertEquals(me.timschneeberger.rootlessjamesdsp.audio.ShellRoutingWorker.MAGIC,data.readInt())
                assertEquals(48000,data.readInt());assertEquals(2,data.readInt())
                repeat(5) { val n=data.readInt();assertTrue(n in 2..960);data.readLong();data.readFully(ByteArray(n*2));worker.outputStream.write(1);worker.outputStream.flush() }
                worker.outputStream.close()
            }
            var exited=false
            val until=started+6000
            while(SystemClock.elapsedRealtime()<until) {
                try {worker.exitValue();exited=true;break} catch (_:IllegalThreadStateException) {SystemClock.sleep(25)}
            }
            assertTrue("Policy owner did not terminate independently: $diagnostics",exited)
            stderr.join(1000)
            assertTrue(diagnostics.toString().contains(if(mode=="init")"TEST_CAPTURE_BLOCKED_AFTER_REGISTRATION" else "TEST_POLICY_CLEANUP_BLOCKED"))
            assertTrue(diagnostics.toString().contains("SHELL_POLICY_OWNER_EXIT"))
            assertFalse("Cleanup unexpectedly completed; fault injection invalid",diagnostics.toString().contains("SHELL_ROUTE_RELEASED"))
            val restored=rms(meter)
            assertTrue("Binder-death cleanup did not restore source: $baseline -> $restored",kotlin.math.abs(restored-baseline)<250)
            android.util.Log.i("ShellRoutingGuardTest","GUARD_RECOVERY mode=$mode baseline=$baseline restored=$restored observationMs=${SystemClock.elapsedRealtime()-started}")
        } finally {process?.destroy();meter?.release();shell("am force-stop org.rv4a.captureprobe")}
    }
}
