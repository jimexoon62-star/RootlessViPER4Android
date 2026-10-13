package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sin

/** Must be executed on Android; compilation is not an execution result. */
@RunWith(AndroidJUnit4::class)
class NativeAudioRecoveryTest {
    @Test fun applicationDiagnosticsArePersistedAndExportable() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val marker = "DIAGNOSTIC_EXPORT_REGRESSION_${System.nanoTime()}"
        timber.log.Timber.i(marker)
        val exported = me.timschneeberger.rootlessjamesdsp.utils.AudioDiagnosticLog.snapshot(context)
        assertTrue(exported.contains("DIAGNOSTIC_START"))
        assertTrue(exported.contains(marker))
        assertTrue(java.io.File(context.filesDir, "audio-diagnostics.log").exists())
    }
    private val callbacks = object : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
    private fun withEngine(test: (Long) -> Unit) {
        val handle = JamesDspWrapper.alloc(callbacks)
        assertTrue("JNI allocation", handle != 0L)
        JamesDspWrapper.setLimiterMode(handle, 2)
        JamesDspWrapper.setPostGain(handle, 0f)
        try { test(handle) } finally { JamesDspWrapper.free(handle) }
    }
    @Test fun repeatedJniStartupAndPassthrough() {
        repeat(10) {
            withEngine { handle ->
                val input = FloatArray(512) { i ->
                    if (i % 2 == 0) (.1 * sin(i * .13)).toFloat()
                    else (.05 * sin(i * .27)).toFloat()
                }
                val output = FloatArray(512)
                repeat(20) { JamesDspWrapper.processFloat(handle, input, output, 0, input.size) }
                assertTrue(output.all { it.isFinite() })
                assertTrue("Non-silent reference must remain non-silent", output.any { kotlin.math.abs(it) > .001f })
                // Fresh DSP has optional effects disabled; limiter is explicitly bypassed.
                assertArrayEquals(input, output, .0001f)
            }
        }
    }
    @Test fun pcm16AndSilenceBoundaries() = withEngine { handle ->
        val input = ShortArray(256) { if (it % 2 == 0) 2000 else -1000 }
        val output = ShortArray(258) { 123 }
        repeat(20) { JamesDspWrapper.processInt16(handle, input, output, 0, input.size) }
        assertEquals(123.toShort(), output[256])
        assertEquals(123.toShort(), output[257])
        for (i in input.indices) assertTrue(kotlin.math.abs(input[i] - output[i]) <= 2)
        val silence = FloatArray(256)
        val result = FloatArray(256)
        repeat(20) { JamesDspWrapper.processFloat(handle, silence, result, 0, silence.size) }
        assertArrayEquals(silence, result, .000001f)
    }
    @Test fun impulseAndGainChangeAreMeasurable() = withEngine { handle ->
        val input = FloatArray(256)
        input[0] = .1f
        input[1] = -.05f
        val output = FloatArray(256)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
        assertArrayEquals(input, output, .000001f)
        JamesDspWrapper.setPostGain(handle, 6f)
        JamesDspWrapper.processFloat(handle, input, output, 0, input.size)
        assertEquals(.199526f, output[0], .00001f)
        assertEquals(-.099763f, output[1], .00001f)
    }
    @Test fun invalidNativeBoundsThrowWithoutCrash() = withEngine { handle ->
        try {
            JamesDspWrapper.processFloat(handle, FloatArray(4), FloatArray(2), 0, 4)
            fail("Oversized output must throw")
        } catch (_: IllegalArgumentException) {}
        try {
            JamesDspWrapper.processInt16(handle, ShortArray(4), ShortArray(4), 0, 3)
            fail("Unaligned stereo input must throw")
        } catch (_: IllegalArgumentException) {}
    }
}
