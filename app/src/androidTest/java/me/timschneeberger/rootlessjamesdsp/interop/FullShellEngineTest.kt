package me.timschneeberger.rootlessjamesdsp.interop

import android.content.*
import android.media.audiofx.Visualizer
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.audio.ShellPcmSource
import me.timschneeberger.rootlessjamesdsp.service.*
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class FullShellEngineTest {
    private val inst=InstrumentationRegistry.getInstrumentation()
    private val context=inst.targetContext
    private fun main(block:()->Unit)=inst.runOnMainSync(block)
    private fun shell(cmd:String):String=inst.uiAutomation.executeShellCommand(cmd).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { r->r.readText() } }
    private fun meter():Visualizer=Visualizer(0).apply {
        setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED)
        setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS); enabled=true
    }
    private fun rms(m:Visualizer):Int {
        SystemClock.sleep(1000)
        return List(8) { val value=Visualizer.MeasurementPeakRms(); assertEquals(0,m.getMeasurementPeakRms(value)); SystemClock.sleep(60); value.mRms }.sorted()[4]
    }
    @Test fun actualFullEngineProcessesOptedOutSourceAndRestoresOnStop() = exercise(0)
    @Test fun lostRendererLeaseRestoresOriginalWithoutAppStop() = exercise(1)
    @Test fun forcedDspExceptionReleasesRoutingAndBoundsRetries() = exercise(2)
    @Test fun aaudio44100SourceUsesFullEngine() = exercise(0,true)
    private fun exercise(failure: Int, aaudio:Boolean=false) {
        val app=context.getSharedPreferences(Constants.PREF_APP,0)
        val output=context.getSharedPreferences(Constants.PREF_OUTPUT,0)
        val oldApp=HashMap(app.all); val oldOutput=HashMap(output.all)
        var m:Visualizer?=null
        val connections=AtomicInteger()
        val fullBlocks=AtomicInteger()
        val failureAt=java.util.concurrent.atomic.AtomicLong()
        val poisoned=java.util.concurrent.atomic.AtomicBoolean()
        val uid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe",0).uid
        try {
            main {
                RootlessAudioProcessorService.stop(context)
                app.edit().putBoolean(ShellAudioProcessorService.MODE_KEY,true).commit()
                output.edit().putFloat(context.getString(R.string.key_output_postgain),-12f).commit()
                ShellAudioProcessorService.authorizationCheck={true}
                ShellAudioProcessorService.sourceFactory={_,rate,uids,monitor->
                    if(poisoned.get()) throw java.io.IOException("Injected unavailable recovery transport")
                    assertEquals(setOf(uid),uids)
                    val index=connections.getAndIncrement()
                    val name="rv4a_full_$index"
                    // Launch the SAME production worker with a separate test-only relay main.
                    shell("CLASSPATH=/data/local/tmp/full-relay.jar:/data/local/tmp/full-app.apk app_process /system/bin ShellTestRelay $name $uid > /data/local/tmp/full-relay-$index.log 2>&1 < /dev/null &")
                    val socket=LocalSocket()
                    var connected=false
                    for(attempt in 0..40) {
                        try { socket.connect(LocalSocketAddress(name)); connected=true; break }
                        catch (_:Exception) { SystemClock.sleep(50) }
                    }
                    check(connected) { "Shell relay failed: "+shell("cat /data/local/tmp/full-relay-$index.log") }
                    DataOutputStream(socket.outputStream).apply { writeInt(rate); writeInt(if(monitor)1 else 0); writeInt(uid); flush() }
                    val delegate=ShellPcmSource.connected(socket.inputStream,socket.outputStream,{socket.close()},if(monitor)1 else 2)
                    object : me.timschneeberger.rootlessjamesdsp.audio.RoutedPcmSource {
                        override fun begin(rate:Int)=delegate.begin(rate)
                        override fun read(output:ShortArray)=delegate.read(output)
                        override fun close()=delegate.close()
                        override fun rendered() {
                            if(!monitor && failure==1 && fullBlocks.incrementAndGet()>500) {
                                if(failureAt.compareAndSet(0,SystemClock.elapsedRealtime())) poisoned.set(true)
                            } else delegate.rendered()
                        }
                    }
                }
            }
            if(failure==2) ShellAudioProcessorService.beforeProcess={
                if(fullBlocks.incrementAndGet()>500) {
                    failureAt.compareAndSet(0,SystemClock.elapsedRealtime()); poisoned.set(true)
                    throw java.io.IOException("Injected DSP processing exception")
                }
            }
            shell("am start -W -n org.rv4a.captureprobe/.DirectToneActivity"+(if(aaudio)" --ez aaudio true" else ""))
            m=meter(); var baseline=rms(m)
            val deadline=SystemClock.elapsedRealtime()+10000
            while(baseline<= -7000 && SystemClock.elapsedRealtime()<deadline) baseline=rms(m)
            assertTrue("Source baseline absent: $baseline",baseline> -7000)
            main { RootlessAudioProcessorService.start(context,null) }
            var processed=rms(m)
            val waitUntil=SystemClock.elapsedRealtime()+15000
            while(processed-baseline !in -1500..-900 && SystemClock.elapsedRealtime()<waitUntil) processed=rms(m)
            assertTrue("Full native gain absent $baseline -> $processed; transports=${connections.get()}",processed-baseline in -1500..-900)
            val start=SystemClock.elapsedRealtime()
            if(failure==0) main { RootlessAudioProcessorService.stop(context) }
            var restored=rms(m)
            while(kotlin.math.abs(restored-baseline)>250 && SystemClock.elapsedRealtime()-start<15000) restored=rms(m)
            assertTrue("Original playback not restored: $baseline -> $restored",kotlin.math.abs(restored-baseline)<250)
            val recovery=SystemClock.elapsedRealtime()-(if(failureAt.get()>0) failureAt.get() else start)
            if(failure!=0) assertTrue("Fault was not injected",failureAt.get()>0)
            assertTrue("Recovery observation exceeded bound: $recovery",recovery<5000)
            android.util.Log.i("FullShellEngineTest","FULL_ENGINE failure=$failure baseline=$baseline processed=$processed restored=$restored recoveryObservationMs=$recovery transports=${connections.get()}")
            if(failure!=0) { SystemClock.sleep(2500); assertTrue("Runaway recovery transports",connections.get()<=4) }
            assertTrue("Monitor and full-rate capture must both execute",connections.get()>=2)
        } finally {
            main {
                RootlessAudioProcessorService.stop(context)
                ShellAudioProcessorService.beforeProcess=null; ShellAudioProcessorService.sourceFactory=null; ShellAudioProcessorService.authorizationCheck=null
                fun restore(p:android.content.SharedPreferences,values:Map<String,*>) { val edit=p.edit().clear(); values.forEach { (key,value)->when(value) { is Boolean->edit.putBoolean(key,value); is Float->edit.putFloat(key,value); is Int->edit.putInt(key,value); is Long->edit.putLong(key,value); is String->edit.putString(key,value) } }; edit.commit() }
                restore(app,oldApp); restore(output,oldOutput)
            }
            m?.release(); shell("am force-stop org.rv4a.captureprobe")
        }
    }
}
