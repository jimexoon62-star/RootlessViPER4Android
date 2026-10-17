package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
class CaptureSignalLeaseTest {
    @Test fun indefinitelyZeroCaptureNeverAuthorizesInitialSuppression() {
        val lease = CaptureSignalLease()
        repeat(10000) { assertFalse(lease.update(true, .1)) }
    }

    @Test fun silentSuccessfulReadsStopAuthorizingMuting() {
        val lease = CaptureSignalLease()
        assertFalse(lease.update(true, 1.0))
        assertTrue(lease.update(false, .01))
        assertTrue(lease.update(true, 1.0))
        repeat(10) { assertFalse(lease.update(true, 1.0)) }
        assertTrue(lease.update(false, .01))
    }
    @Test fun intermittentSilenceAndNormalMusicKeepLease() {
        val lease = CaptureSignalLease()
        assertTrue(lease.update(false, .1))
        repeat(100) {
            assertTrue(lease.update(true, 1.0))
            assertTrue(lease.update(false, .1))
        }
    }
}
