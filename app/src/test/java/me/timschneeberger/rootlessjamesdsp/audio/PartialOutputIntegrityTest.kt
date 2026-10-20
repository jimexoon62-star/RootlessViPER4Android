package me.timschneeberger.rootlessjamesdsp.audio
import me.timschneeberger.rootlessjamesdsp.utils.OutputIntegrity
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
class PartialOutputIntegrityTest {
    @Test fun staleOutputTailCannotMaskSilentActualOutput() {
        val input=ShortArray(9600) {1000}; val output=ShortArray(9600) {if(it>=4800)4096 else 0}
        val integrity=OutputIntegrity()
        try { repeat(61) { integrity.check(input,output,48000,4800) }; fail("Valid output prefix was silent for more than 3 seconds") }
        catch (_:IOException) {}
    }
    @Test fun staleInputTailCannotTurnSilentCaptureIntoFailure() {
        val input=ShortArray(9600) {if(it>=4800)4096 else 0}; val output=ShortArray(9600)
        val integrity=OutputIntegrity()
        repeat(100) { integrity.check(input,output,48000,4800) }
        assertEquals(0.0,integrity.inputPeak,0.0)
    }
}
