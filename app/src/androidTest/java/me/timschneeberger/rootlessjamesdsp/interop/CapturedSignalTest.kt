package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.platform.app.InstrumentationRegistry
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Requires the separate synthetic probe result; absence is a failure, never a pass.
 * Captured signal -> real JNI gain -> AudioTrack API progress. No audible claim.
 */
class CapturedSignalTest {
    @Test fun capturedReferenceTonePassesNativeDspAndOutput() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("probeResultPath")
            ?: error("Run the synthetic capture fixture first")
        require(path.matches(Regex("/data/local/tmp/[A-Za-z0-9_.-]+")))
        val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $path")
        val result = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.let(::JSONObject)
        assertTrue(result.toString(), result.optBoolean("complete"))
        for (name in listOf("allowed", "allowedAgain")) {
            val stage = result.getJSONObject(name)
            assertTrue("No captured signal at $name", stage.getDouble("rms") > .01)
            assertTrue(stage.getLong("samples") > 48000)
            assertTrue(stage.getInt("head") > 48000)
        }
        val restricted = result.getJSONObject("systemOnly")
        assertTrue("Blocked capture must still return frames", restricted.getLong("samples") > 48000)
        assertTrue("Expected restricted signal suppression", restricted.getDouble("rms") < .00001)
        val values = result.getJSONObject("allowed").getJSONArray("pcm")
        assertEquals(512, values.length())
        val input = FloatArray(values.length()) { values.getDouble(it).toFloat() }
        val output = FloatArray(input.size)
        val callbacks = object : JamesDspWrapper.JamesDspCallbacks {
            override fun onLiveprogOutput(message: String) {}
            override fun onLiveprogExec(id: String) {}
            override fun onLiveprogResult(resultCode: Int,id: String,errorMessage: String?) {}
            override fun onVdcParseError() {}
            override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
        }
        val handle = JamesDspWrapper.alloc(callbacks)
        assertTrue(handle != 0L)
        try {
            JamesDspWrapper.setLimiterMode(handle, 2)
            JamesDspWrapper.setPostGain(handle, 6f)
            JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
            for (i in input.indices) assertEquals("PCM sample $i", input[i] * 1.9952623f, output[i], .0001f)
        } finally { JamesDspWrapper.free(handle) }
        val track = android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setAllowedCapturePolicy(android.media.AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(16384).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        try {
            track.play()
            val end = android.os.SystemClock.elapsedRealtime() + 1500
            var frames = 0
            while (android.os.SystemClock.elapsedRealtime() < end) {
                var offset = 0
                while (offset < output.size) {
                    val count = track.write(output, offset, output.size - offset, android.media.AudioTrack.WRITE_NON_BLOCKING)
                    assertTrue("write error $count", count >= 0 && count % 2 == 0)
                    if (count == 0) {
                        assertTrue("Output stalled", android.os.SystemClock.elapsedRealtime() < end + 500)
                        Thread.sleep(3)
                    } else { offset += count; frames += count / 2 }
                }
            }
            assertTrue(frames > 4800)
            assertTrue("Output must advance beyond API writes", track.playbackHeadPosition > 4800)
        } finally { track.pause(); track.flush(); track.release() }
    }
}
