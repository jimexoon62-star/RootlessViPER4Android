package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
class CaptureSignalLeaseTest {
    @Test fun silentSuccessfulReadsStopAuthorizingMuting() {
        val lease = CaptureSignalLease()
        assertTrue(lease.update(true, 1.0))
        repeat(10) { assertFalse(lease.update(true, 1.0)) }
        assertTrue(lease.update(false, .01))
    }
    @Test fun intermittentSilenceAndNormalMusicKeepLease() {
        val lease = CaptureSignalLease()
        repeat(100) {
            assertTrue(lease.update(true, 1.0))
            assertTrue(lease.update(false, .1))
        }
    }
}
