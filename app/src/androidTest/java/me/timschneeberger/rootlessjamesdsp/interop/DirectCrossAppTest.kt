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
    @Test fun officialVlcPlayerReceivesServiceGain() = measure("1", "org.videolan.vlc")
    @Test fun officialMpvPlayerReceivesServiceGain() = measure("1", "is.xyz.mpv")
    private fun measure(method:String, player:String?=null) {
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
        var toneFile:java.io.File?=null
        var toneUri:android.net.Uri?=null
        try {
            main {
                RootlessAudioProcessorService.stop(context)
                app.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,true)
                    .putString(context.getString(R.string.key_session_detection_method),method).commit()
                output.edit().putFloat(context.getString(R.string.key_output_postgain),0f).commit()
                eq.edit().putBoolean(context.getString(R.string.key_eq_enable),false).commit()
            }
            if(player==null) {
                val launch=shell("am start -W -n org.rv4a.captureprobe/.DirectToneActivity")
                assertFalse(launch,launch.contains("Error"))
            } else {
                if(player=="org.videolan.vlc") {
                    // VLC 3.6.3 gates even a granted content URI on its normal
                    // first-run storage approval. Use the real Settings UI,
                    // in this synthetic-only emulator; no app-op bypass.
                    shell("am start -W -a android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION -d package:org.videolan.vlc")
                    fun findSwitch(node:android.view.accessibility.AccessibilityNodeInfo?):android.view.accessibility.AccessibilityNodeInfo? {
                        if(node==null) return null
                        if(node.isCheckable && node.isEnabled) return node
                        for(i in 0 until node.childCount) findSwitch(node.getChild(i))?.let { return it }
                        return null
                    }
                    val deadline=SystemClock.elapsedRealtime()+5000
                    var toggle=findSwitch(instrumentation.uiAutomation.rootInActiveWindow)
                    while(toggle==null && SystemClock.elapsedRealtime()<deadline) {
                        SystemClock.sleep(100); toggle=findSwitch(instrumentation.uiAutomation.rootInActiveWindow)
                    }
                    assertNotNull("VLC storage approval UI not available",toggle)
                    if(!toggle!!.isChecked) {
                        var clickable=toggle!!
                        while(!clickable.isClickable && clickable.parent!=null) clickable=clickable.parent
                        assertTrue("VLC storage approval click failed",clickable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                    }
                    SystemClock.sleep(300)
                    assertTrue("VLC storage approval was not granted",shell("appops get org.videolan.vlc MANAGE_EXTERNAL_STORAGE").contains("allow"))
                }
                val file=java.io.File(context.filesDir,"synthetic-cross-app.wav"); toneFile=file
                val frames=48000*60
                fun bytes(size:Int)=java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                file.outputStream().use { out ->
                    val header=bytes(44).put("RIFF".toByteArray()).putInt(36+frames*4).put("WAVEfmt ".toByteArray())
                        .putInt(16).putShort(1).putShort(2).putInt(48000).putInt(48000*4).putShort(4).putShort(16)
                        .put("data".toByteArray()).putInt(frames*4)
                    out.write(header.array())
                    var frame=0
                    repeat(600) {
                        val block=bytes(4800*4)
                        repeat(4800) {
                            val value=(.2*32767*kotlin.math.sin(2*Math.PI*997*frame++/48000)).toInt().toShort()
                            block.putShort(value).putShort(value)
                        }
                        out.write(block.array())
                    }
                }
                val uri=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.dump_provider",file)
                toneUri=uri
                main {
                    context.startActivity(Intent(Intent.ACTION_VIEW).setPackage(player).setDataAndType(uri,"audio/wav")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
                SystemClock.sleep(2000)
            }
            val probeUid=context.packageManager.getApplicationInfo(player ?: "org.rv4a.captureprobe",0).uid
            assertNotEquals("fixture must be another UID",android.os.Process.myUid(),probeUid)
            if(player==null) {
                val secondUid=context.packageManager.getApplicationInfo("org.rv4a.captureprobe.second",0).uid
                assertNotEquals("fixtures must have distinct UIDs",probeUid,secondUid)
                assertNotEquals(android.os.Process.myUid(),secondUid)
            }
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
            val singleSource=rms(); assertTrue("single source baseline=$singleSource",singleSource> -7000)
            val baseline=if(player==null) {
                val secondLaunch=shell("am start -W -n org.rv4a.captureprobe.second/org.rv4a.captureprobe.DirectToneActivity --ei frequency 1499 --ez aaudio true")
                assertFalse(secondLaunch,secondLaunch.contains("Error"))
                rms().also { assertTrue("AAudio source must contribute real signal: $singleSource -> $it",it-singleSource in 200..400) }
            } else singleSource
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
            android.util.Log.i("DirectCrossAppTest","CROSS_APP_MEASUREMENT method=$method singleSource_mB=$singleSource baseline_mB=$baseline reduced_mB=$reduced restored_mB=$restored source=${player ?: "AudioTrack+AAudio:SYSTEM_ONLY"}")
        } finally {
            main { RootlessAudioProcessorService.stop(context) }
            meter?.release()
            shell("am force-stop org.rv4a.captureprobe")
            shell("am force-stop org.rv4a.captureprobe.second")
            if(player!=null) shell("am force-stop $player")
            toneUri?.let { context.revokeUriPermission(it,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            toneFile?.delete()
            main { restore(app,oldApp); restore(output,oldOutput); restore(eq,oldEq) }
        }
    }
}
