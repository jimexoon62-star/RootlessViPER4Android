package me.timschneeberger.rootlessjamesdsp.utils
import org.junit.Assert.*
import org.junit.Test
class RouteChangeDetectorTest {
    @Test fun initialRoutingDoesNotCreateRestartLoop() {
        val route = RouteChangeDetector()
        assertFalse(route.changed(null)); assertFalse(route.changed(1))
        repeat(100) { assertFalse(route.changed(1)) }
    }
    @Test fun connectedDeviceSwitchIsDetectedAcrossTemporaryNullRoute() {
        val route = RouteChangeDetector()
        assertFalse(route.changed(1)); assertFalse(route.changed(null))
        assertTrue(route.changed(2)); assertFalse(route.changed(2)); assertTrue(route.changed(1))
    }
}
