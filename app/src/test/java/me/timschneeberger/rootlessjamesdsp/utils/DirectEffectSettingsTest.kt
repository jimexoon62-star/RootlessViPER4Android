package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
class DirectEffectSettingsTest {
    @Test fun validEqPreservesStereoHeadroomWithoutOverwritingProfile() {
        val bands=(DirectEffectSettings.centers + List(15) {6f}).joinToString(";")
        val s=DirectEffectSettings.parse(true,bands,0f)
        assertEquals(-6f,s.inputGain,0f); assertEquals(15,s.cutoffs.size)
        assertTrue(s.cutoffs.zipWithNext().all {it.first < it.second})
        assertEquals(0f,DirectEffectSettings.parse(false,bands,0f).inputGain,0f)
    }
    @Test fun malformedEqFallsBackToFlat() {
        listOf("NaN", "", List(30){"0"}.joinToString(";")).forEach {
            val s=DirectEffectSettings.parse(true,it,-12f)
            assertTrue(s.gains.all {v->v==0f}); assertEquals(-12f,s.inputGain,0f)
        }
    }
    @Test fun gainIsBoundedAwayFromMute() {
        assertEquals(-15f,DirectEffectSettings.parse(false,null,-200f).inputGain,0f)
        assertEquals(15f,DirectEffectSettings.parse(false,null,200f).inputGain,0f)
    }
    @Test(expected=IllegalArgumentException::class) fun invalidGainRejected() {
        DirectEffectSettings.parse(false,null,Float.NaN)
    }
}
