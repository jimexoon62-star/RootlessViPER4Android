package me.timschneeberger.rootlessjamesdsp.interop

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Visualizer
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.utils.DirectAudioEffect
import me.timschneeberger.rootlessjamesdsp.utils.DirectEffectSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/** Measures only a self-owned synthetic source, never Spotify or another app.
 * Its native capture opt-out remains set for the entire test. */
@RunWith(AndroidJUnit4::class)
class DirectSessionEffectTest {
    @Test fun directGainChangesOptedOutSourceAndReleaseRestoresIt() {
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(32768).setTransferMode(AudioTrack.MODE_STREAM).build()
        val stopped=AtomicBoolean(false); val failed=AtomicReference<Throwable?>()
        val writer=Thread {
            try {
                val pcm=FloatArray(960)
                var frame=0L; var last=SystemClock.elapsedRealtime()
                while (!stopped.get()) {
                    for (i in pcm.indices step 2) {
                        val v=(.2*sin(2*PI*997*frame++/48000)).toFloat(); pcm[i]=v; pcm[i+1]=v
                    }
                    var offset=0
                    while (offset<pcm.size && !stopped.get()) {
                        val n=track.write(pcm,offset,pcm.size-offset,AudioTrack.WRITE_NON_BLOCKING)
                        check(n>=0 && n%2==0)
                        if (n>0) { offset+=n; last=SystemClock.elapsedRealtime() }
                        else { check(SystemClock.elapsedRealtime()-last<3000); Thread.sleep(2) }
                    }
                }
            } catch (e: Throwable) { if(!stopped.get()) failed.set(e) }
        }
        var effect: DirectAudioEffect?=null
        var visualizer: Visualizer?=null
        try {
            track.play(); writer.start()
            // AOSP session Visualizer is INSERT_FIRST and DynamicsProcessing
            // INSERT_LAST: a session meter observes pre-DSP samples. Observe
            // the output mix AFTER our session insert, on this disposable
            // synthetic-only emulator. This is test code, never production.
            val meter=Visualizer(0); visualizer=meter
            assertEquals(Visualizer.SUCCESS,meter.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED))
            assertEquals(Visualizer.SUCCESS,meter.setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS))
            assertEquals(Visualizer.SUCCESS,meter.setEnabled(true))
            effect=DirectAudioEffect(track.audioSessionId,DirectEffectSettings.parse(false,null,0f)) { error("unexpected control loss") }
            fun rms(): Int {
                SystemClock.sleep(1200)
                val values=mutableListOf<Int>()
                repeat(8) {
                    val m=Visualizer.MeasurementPeakRms()
                    assertEquals(Visualizer.SUCCESS,meter.getMeasurementPeakRms(m))
                    values+=m.mRms; SystemClock.sleep(60)
                }
                return values.sorted()[values.size/2]
            }
            // The output mix includes the device media-volume curve. The
            // Visualizer floor is -9600 mB; leave 26 dB above it before the
            // attenuation step rather than misclassifying quiet output as zero.
            val baseline=rms(); assertTrue("baseline below reliable meter range: $baseline",baseline > -7000)
            effect.update(DirectEffectSettings.parse(false,null,-12f))
            val reduced=rms()
            assertTrue("expected -12dB, baseline=$baseline reduced=$reduced", reduced-baseline in -1500..-900)
            val eqBands=(DirectEffectSettings.centers + List(15) { -12f }).joinToString(";")
            effect.update(DirectEffectSettings.parse(true,eqBands,0f))
            val equalized=rms()
            assertTrue("EQ did not change source: $baseline -> $equalized", equalized-baseline in -1600..-800)
            effect.close(); effect=null
            val restored=rms()
            assertTrue("release did not restore source: $baseline -> $restored",kotlin.math.abs(restored-baseline)<250)
            assertNull(failed.get()); assertTrue(track.playbackHeadPosition>48000)
            android.util.Log.i("DirectSessionEffectTest","DIRECT_MEASUREMENT baseline_mB=$baseline reduced_mB=$reduced equalized_mB=$equalized restored_mB=$restored capturePolicy=SYSTEM_ONLY")
        } finally {
            stopped.set(true); writer.interrupt(); writer.join(1000)
            visualizer?.release(); effect?.close(); track.stop(); track.release()
        }
    }
}
