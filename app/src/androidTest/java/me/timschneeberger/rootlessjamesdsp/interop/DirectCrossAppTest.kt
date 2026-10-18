package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Intent
import android.media.audiofx.Visualizer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.DirectAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import org.junit.Assert.*
import org.junit.Test

/** Disposable emulator only. Measures the real service processing a different UID;
 * never feeds a discovered session ID directly into an effect constructor. */
class DirectCrossAppTest {
    @Test fun audioServiceDiscoveryProcessesTwoOptedOutApps() = measure("1")
    @Test fun audioPolicyDiscoveryProcessesTwoOptedOutApps() = measure("0")
    private fun measure(method:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        fun main(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun shell(command:String):String = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
        val app=context.getSharedPreferences(Constants.PREF_APP,0)
        val output=context.getSharedPreferences(Constants.PREF_OUTPUT,0)
        val eq=context.getSharedPreferences(Constants.PREF_EQ,0)
        val oldApp=app.all; val oldOutput=output.all; val oldEq=eq.all
        fun restore(p:android.content.SharedPreferences, values:Map<String,*>) {
            val edit=p.edit().clear()
            values.forEach { (key,value)->when(value) {
                is Boolean -> edit.putBoolean(key,value)
                is String -> edit.putString(key,value)
                is Float -> edit.putFloat(key,value)
                is Int -> edit.putInt(key,value)
                is Long -> edit.putLong(key,value)
                is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key,value as Set<String>)
            } }; edit.commit()
        }
        var meter:Visualizer?=null
        try {
            main {
                RootlessAudioProcessorService.stop(context)
                app.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,true)
                    .putString(context.getString(R.string.key_session_detection_method),method).commit()
                output.edit().putFloat(context.getString(R.string.key_output_postgain),0f).commit()
                eq.edit().putBoolean(context.getString(R.string.key_eq_enable),false).commit()
            }
            val launch=shell("am start -W -n org.rv4a.captureprobe/.DirectToneActivity")
            assertFalse(launch,launch.contains("Error"))
            val probeUid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe",0).uid
            assertNotEquals("fixture must be another UID",android.os.Process.myUid(),probeUid)
            val secondUid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe.second",0).uid
            assertNotEquals("fixtures must have distinct UIDs",probeUid,secondUid)
            assertNotEquals(android.os.Process.myUid(),secondUid)
            val secondLaunch=shell("am start -W -n org.rv4a.captureprobe.second/org.rv4a.captureprobe.DirectToneActivity --ei frequency 1499")
            assertFalse(secondLaunch,secondLaunch.contains("Error"))
            val m=Visualizer(0); meter=m
            assertEquals(Visualizer.SUCCESS,m.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED))
            assertEquals(Visualizer.SUCCESS,m.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS))
            assertEquals(Visualizer.SUCCESS,m.setEnabled(true))
            fun rms():Int {
                SystemClock.sleep(1200)
                return List(8) {
                    val value=Visualizer.MeasurementPeakRms()
                    assertEquals(Visualizer.SUCCESS,m.getMeasurementPeakRms(value))
                    SystemClock.sleep(60); value.mRms
                }.sorted()[4]
            }
            val baseline=rms(); assertTrue("baseline=$baseline",baseline> -7000)
            main { RootlessAudioProcessorService.start(context,null) }
            val deadline=SystemClock.elapsedRealtime()+10000
            var controlled=false
            while(!controlled && SystemClock.elapsedRealtime()<deadline) {
                main { controlled=(BaseAudioProcessorService.liveInstances().filterIsInstance<DirectAudioProcessorService>()
                    .singleOrNull()?.currentCaptureStatus == me.timschneeberger.rootlessjamesdsp.utils.CaptureStatus.DIRECT_EFFECTS) }
                SystemClock.sleep(100)
            }
            assertTrue("real service did not discover/control separate opted-out source",controlled)
            main {
                output.edit().putFloat(context.getString(R.string.key_output_postgain),-12f).commit()
                context.sendLocalBroadcast(Intent(Constants.ACTION_PREFERENCES_UPDATED))
            }
            val reduced=rms()
            assertTrue("cross-app service gain missing: $baseline -> $reduced",reduced-baseline in -1500..-900)
            main { RootlessAudioProcessorService.stop(context) }
            val restored=rms()
            assertTrue("service stop failed to restore source: $baseline -> $restored",kotlin.math.abs(restored-baseline)<250)
            android.util.Log.i("DirectCrossAppTest","CROSS_APP_MEASUREMENT method=$method baseline_mB=$baseline reduced_mB=$reduced restored_mB=$restored separateApps=2 capturePolicy=SYSTEM_ONLY")
        } finally {
            main { RootlessAudioProcessorService.stop(context) }
            meter?.release()
            shell("am force-stop org.rv4a.captureprobe")
            shell("am force-stop org.rv4a.captureprobe.second")
            main { restore(app,oldApp); restore(output,oldOutput); restore(eq,oldEq) }
        }
    }
}
