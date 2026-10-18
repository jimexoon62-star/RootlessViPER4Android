package me.timschneeberger.rootlessjamesdsp.interop

import android.content.*
import android.media.audiofx.Visualizer
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
    @Test fun actualShizukuConsentAndRemoteProcessRunsCompleteEngine() = exercise(3)
    @Test fun physicalRouteCallbackRetiresAndRecoversGeneration() = exercise(4)
    @Test fun heldBinderShutdownAndRepeatedEnableDisableReleaseRouting() = exercise(5)
    private fun exercise(failure: Int, aaudio:Boolean=false) {
        val app=context.getSharedPreferences(Constants.PREF_APP,0)
        val output=context.getSharedPreferences(Constants.PREF_OUTPUT,0)
        val oldApp=HashMap(app.all); val oldOutput=HashMap(output.all)
        var m:Visualizer?=null
        var heldConnection:ServiceConnection?=null
        val connections=AtomicInteger()
        val fullBlocks=AtomicInteger()
        val failureAt=java.util.concurrent.atomic.AtomicLong()
        val poisoned=java.util.concurrent.atomic.AtomicBoolean()
        val uid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe",0).uid
        try {
            run {
                val accessibility=inst.uiAutomation.serviceInfo
                accessibility.flags=accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                inst.uiAutomation.serviceInfo=accessibility
                val until=SystemClock.elapsedRealtime()+10000
                while(!rikka.shizuku.Shizuku.pingBinder() && SystemClock.elapsedRealtime()<until) SystemClock.sleep(100)
                assertTrue("Actual Shizuku binder unavailable",rikka.shizuku.Shizuku.pingBinder())
                assertEquals(2000,rikka.shizuku.Shizuku.getUid())
                if(rikka.shizuku.Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    main { rikka.shizuku.Shizuku.requestPermission(992) }
                    var clicked=false
                    val permissionUntil=SystemClock.elapsedRealtime()+10000
                    while(!clicked && SystemClock.elapsedRealtime()<permissionUntil) {
                        val nodes=inst.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Allow all the time").orEmpty()
                        clicked=nodes.firstOrNull()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)==true
                        if(!clicked) SystemClock.sleep(100)
                    }
                    assertTrue("Official Shizuku consent dialog not found",clicked)
                    val grantedUntil=SystemClock.elapsedRealtime()+5000
                    while(rikka.shizuku.Shizuku.checkSelfPermission()!=0 && SystemClock.elapsedRealtime()<grantedUntil) SystemClock.sleep(100)
                    assertEquals(0,rikka.shizuku.Shizuku.checkSelfPermission())
                }
            }
            main {
                RootlessAudioProcessorService.stop(context)
                app.edit().putBoolean(ShellAudioProcessorService.MODE_KEY,true).commit()
                output.edit().putFloat(context.getString(R.string.key_output_postgain),-12f).commit()
                ShellAudioProcessorService.sourceFactory={_,rate,uids,monitor->
                    if(poisoned.get()) throw java.io.IOException("Injected unavailable recovery transport")
                    assertEquals(setOf(uid),uids)
                    connections.incrementAndGet()
                    val delegate=ShellPcmSource.open(context,rate,uids,monitor)
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
            if(failure==3) ShellAudioProcessorService.sourceFactory=null
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
            if(failure==4) {
                val before=connections.get()
                main {
                    val service=BaseAudioProcessorService.liveInstances().filterIsInstance<ShellAudioProcessorService>().single()
                    val field=ShellAudioProcessorService::class.java.getDeclaredField("routes").apply { isAccessible=true }
                    val callback=field.get(service) as android.media.AudioDeviceCallback
                    val devices=context.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        .filter { it.isSink && it.type!=android.media.AudioDeviceInfo.TYPE_REMOTE_SUBMIX }.toTypedArray()
                    assertTrue(devices.isNotEmpty()); callback.onAudioDevicesAdded(devices)
                }
                var recovered=rms(m)
                val until=SystemClock.elapsedRealtime()+15000
                while(recovered-baseline !in -1500..-900 && SystemClock.elapsedRealtime()<until) recovered=rms(m)
                assertTrue("Route callback did not recover native DSP",recovered-baseline in -1500..-900)
                assertTrue("Route callback did not replace the generation",connections.get()>before)
                assertEquals(1,BaseAudioProcessorService.activeServices)
            }
            if(failure==5) {
                val bound=java.util.concurrent.CountDownLatch(1)
                val connection=object:ServiceConnection {
                    override fun onServiceConnected(name:ComponentName,binder:android.os.IBinder) { bound.countDown() }
                    override fun onServiceDisconnected(name:ComponentName) {}
                }
                main { assertTrue(context.bindService(Intent(context,ShellAudioProcessorService::class.java),connection,Context.BIND_AUTO_CREATE)); heldConnection=connection }
                assertTrue(bound.await(3,java.util.concurrent.TimeUnit.SECONDS))
            }
            val start=SystemClock.elapsedRealtime()
            if(failure==0 || failure>=3) main { RootlessAudioProcessorService.stop(context) }
            var restored=rms(m)
            while(kotlin.math.abs(restored-baseline)>250 && SystemClock.elapsedRealtime()-start<15000) restored=rms(m)
            assertTrue("Original playback not restored: $baseline -> $restored",kotlin.math.abs(restored-baseline)<250)
            val recovery=SystemClock.elapsedRealtime()-(if(failureAt.get()>0) failureAt.get() else start)
            if(failure in 1..2) assertTrue("Fault was not injected",failureAt.get()>0)
            assertTrue("Recovery observation exceeded bound: $recovery",recovery<5000)
            android.util.Log.i("FullShellEngineTest","FULL_ENGINE failure=$failure baseline=$baseline processed=$processed restored=$restored recoveryObservationMs=$recovery transports=${connections.get()}")
            if(failure in 1..2) { SystemClock.sleep(2500); assertTrue("Runaway recovery transports",connections.get()<=4) }
            if(failure==5) {
                assertTrue(BaseAudioProcessorService.liveInstances().filterIsInstance<ShellAudioProcessorService>().single().hasStoppedProcessing)
                main { context.unbindService(heldConnection!!); heldConnection=null }
                repeat(2) {
                    val untilStopped=SystemClock.elapsedRealtime()+3000
                    while(BaseAudioProcessorService.activeServices!=0 && SystemClock.elapsedRealtime()<untilStopped) SystemClock.sleep(50)
                    assertEquals(0,BaseAudioProcessorService.activeServices)
                    main { RootlessAudioProcessorService.start(context,null) }
                    var active=rms(m)
                    val until=SystemClock.elapsedRealtime()+15000
                    while(active-baseline !in -1500..-900 && SystemClock.elapsedRealtime()<until) active=rms(m)
                    assertTrue("Repeated start failed",active-baseline in -1500..-900)
                    main { RootlessAudioProcessorService.stop(context) }
                    val direct=rms(m)
                    assertTrue("Repeated stop left source suppressed",kotlin.math.abs(direct-baseline)<250)
                }
            }
            if(failure==2) {
                // Keep the original player alive: the new player has the SAME UID,
                // so only a new session identity can reopen an exhausted budget.
                poisoned.set(false); ShellAudioProcessorService.beforeProcess=null
                val previous=connections.get()
                shell("am start -W -f 0x18000000 -n org.rv4a.captureprobe/.DirectToneActivity --ei frequency 1499")
                var reattached=rms(m)
                val until=SystemClock.elapsedRealtime()+15000
                while(reattached-baseline !in -1200..-600 && SystemClock.elapsedRealtime()<until) reattached=rms(m)
                assertTrue("Same-UID new session did not recover full DSP: $baseline -> $reattached",reattached-baseline in -1200..-600)
                assertTrue("No new capture generation for the new session",connections.get()>previous)
                android.util.Log.i("FullShellEngineTest","SESSION_REATTACH sameUID=true baseline=$baseline reattached=$reattached transports=${connections.get()}")
            }
            if(failure!=3) assertTrue("Monitor and full-rate capture must both execute",connections.get()>=2)
        } finally {
            main {
                RootlessAudioProcessorService.stop(context)
                heldConnection?.let { context.unbindService(it); heldConnection=null }
                ShellAudioProcessorService.beforeProcess=null; ShellAudioProcessorService.sourceFactory=null;
                fun restore(p:android.content.SharedPreferences,values:Map<String,*>) { val edit=p.edit().clear(); values.forEach { (key,value)->when(value) { is Boolean->edit.putBoolean(key,value); is Float->edit.putFloat(key,value); is Int->edit.putInt(key,value); is Long->edit.putLong(key,value); is String->edit.putString(key,value) } }; edit.commit() }
                restore(app,oldApp); restore(output,oldOutput)
            }
            m?.release(); shell("am force-stop org.rv4a.captureprobe")
        }
    }
}
