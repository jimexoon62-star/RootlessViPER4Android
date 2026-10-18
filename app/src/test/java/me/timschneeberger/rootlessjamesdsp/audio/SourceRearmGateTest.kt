package me.timschneeberger.rootlessjamesdsp.audio
import me.timschneeberger.rootlessjamesdsp.utils.SourceRearmGate
import org.junit.Assert.*
import org.junit.Test
class SourceRearmGateTest {
    @Test fun emptySnapshotsCannotReplenishExhaustedSessionRearm() {
        val gate=SourceRearmGate(); assertTrue(gate.observe(setOf(10042))); assertTrue(gate.newSession())
        repeat(10) { assertFalse(gate.observe(emptySet())); assertFalse(gate.observe(setOf(10042))); assertFalse(gate.newSession()) }
    }
    @Test fun genuinelyDifferentOwnerCanRecover() {
        val gate=SourceRearmGate(); gate.observe(setOf(10042)); gate.newSession()
        assertTrue(gate.observe(setOf(10043))); assertTrue(gate.newSession()); assertFalse(gate.newSession())
    }
    @Test fun confirmedHealthyProgressAllowsLaterIndependentFailureRecovery() {
        val gate=SourceRearmGate(); gate.observe(setOf(10042)); gate.newSession()
        gate.confirmedHealthy(); assertTrue(gate.newSession()); assertFalse(gate.newSession())
    }
}
