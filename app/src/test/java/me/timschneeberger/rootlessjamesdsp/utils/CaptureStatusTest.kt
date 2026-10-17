package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.assertEquals
import org.junit.Test
class CaptureStatusTest {
    @Test fun enabledServiceAndRunningTrackDoNotProveProcessing() {
        assertEquals(CaptureStatus.CHECKING, CaptureStatus.resolve(false,false,false,true,true))
        assertEquals(CaptureStatus.CHECKING, CaptureStatus.resolve(false,false,true,false,true))
        assertEquals(CaptureStatus.CHECKING, CaptureStatus.resolve(false,false,true,true,false))
    }
    @Test fun signalOwnershipAndOutputProgressAreAllRequired() {
        assertEquals(CaptureStatus.SIGNAL_PROGRESS, CaptureStatus.resolve(false,false,true,true,true))
    }
    @Test fun blockedSourceRemainsIdentifiedWithoutMutingIt() {
        assertEquals(CaptureStatus.RESTRICTED, CaptureStatus.resolve(false,true,false,false,true))
    }
    @Test fun failureOverridesStaleProgress() {
        assertEquals(CaptureStatus.DIRECT, CaptureStatus.resolve(true,true,true,true,true))
    }
}
