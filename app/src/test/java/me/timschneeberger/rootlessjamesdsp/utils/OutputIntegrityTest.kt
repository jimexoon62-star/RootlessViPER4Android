package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Test
import java.io.IOException
class OutputIntegrityTest {
    @Test fun pausedAndSilentContentNeverFails() {
        val guard = OutputIntegrity()
        repeat(100) { guard.check(FloatArray(2000), FloatArray(2000), 1000) }
    }
    @Test(expected = IOException::class) fun successfulIoWithDeadDspIsDetected() {
        val guard = OutputIntegrity()
        repeat(3) { guard.check(FloatArray(2000) { .1f }, FloatArray(2000), 1000) }
    }
    @Test fun normalStereoAndTemporaryZerosRemainValid() {
        val guard = OutputIntegrity()
        repeat(20) {
            guard.check(ShortArray(2000) { 1000 }, ShortArray(2000), 1000)
            guard.check(ShortArray(2000) { 1000 }, ShortArray(2000) { -1000 }, 1000)
        }
    }
    @Test(expected = IOException::class) fun nonFiniteOutputFailsBeforeWrite() {
        OutputIntegrity().check(floatArrayOf(.1f, -.1f), floatArrayOf(Float.NaN, 0f), 48000)
    }
}
