package me.timschneeberger.rootlessjamesdsp.service

import android.content.Context
import android.media.audiofx.AudioEffect
import android.os.Handler
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionManager
import me.timschneeberger.rootlessjamesdsp.utils.MutedAudioEffectFactory
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Executes the actual synchronous service failure path with Android adapters mocked.
 * Handler work is deliberately not executed: assertion is BEFORE async teardown.
 */
class FailOpenServiceTest {
    private class Fixture {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val db = RootlessSessionDatabase(mock(Context::class.java), factory)
        val service = RootlessAudioProcessorService()
        val effect = mock(AudioEffect::class.java)
        init {
            val manager = mock(RootlessSessionManager::class.java)
            `when`(manager.sessionDatabase).thenReturn(db)
            set("sessionManager", manager)
            set("mainHandler", mock(Handler::class.java))
            `when`(factory.make(100, "player")).thenReturn(effect)
            db.resumeMuting(); db.addSession(100, 1000, "player")
        }
        fun set(name: String, value: Any) {
            service.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
        }
        fun field(name: String): Any = service.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(service)
        fun fail() {
            service.javaClass.getDeclaredMethod("failOpen", String::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(service, "injected DSP/endpoint failure", false)
        }
        fun assertReleased() {
            verify(factory).release(effect)
            assertTrue(db.sessionList.isEmpty())
            db.addSession(101, 1000, "late player")
            verify(factory, never()).make(101, "late player")
        }
    }
    @Test fun fatalFailureReleasesBeforeHandlerRuns() {
        val f = Fixture(); f.fail(); f.assertReleased()
    }
    @Test fun simultaneousFatalFailuresAreIdempotent() {
        val f = Fixture()
        val threads = List(8) { Thread { f.fail() }.apply { start() } }
        threads.forEach { it.join(2000); assertFalse("deadlock", it.isAlive) }
        f.assertReleased()
    }
    @Test fun stalledWatchdogReleasesOriginalEffects() {
        val f = Fixture()
        f.set("pipelineIdle", false); f.set("lastProgress", -4000L)
        (f.field("watchdog") as Runnable).run()
        f.assertReleased()
    }
    @Test fun idleWatchdogDoesNotKillPausedSession() {
        val f = Fixture()
        f.set("pipelineIdle", true); f.set("lastProgress", -4000L)
        (f.field("watchdog") as Runnable).run()
        verify(f.factory, never()).release(f.effect)
    }
    @Test fun sessionControlLossUsesFailOpenPath() {
        val f = Fixture()
        (f.field("onSessionLossListener") as RootlessSessionDatabase.OnSessionLossListener).onSessionLost(100)
        f.assertReleased()
    }
    @Test fun projectionRevocationReleasesBeforeAnyUiOrTeardown() {
        val f = Fixture()
        (f.field("projectionCallback") as android.media.projection.MediaProjection.Callback).onStop()
        f.assertReleased()
        assertTrue((f.field("terminalFailure") as java.util.concurrent.atomic.AtomicBoolean).get())
    }
    @Test fun bluetoothRouteChangeReleasesBeforeDebouncedRestart() {
        val f = Fixture()
        f.set("recorderThread", Thread {})
        (f.field("devices") as android.media.AudioDeviceCallback).onAudioDevicesRemoved(emptyArray())
        f.assertReleased()
        assertTrue(f.field("isProcessorDisposing") as Boolean)
    }
    @Test fun rapidPolicyRecreationRequestsCoalesceAndShutdownRejectsThem() {
        val f = Fixture()
        repeat(100) { f.service.requestAudioRecordRecreation() }
        assertTrue(f.field("recreateRecorderRequested") as Boolean)
        f.set("isProcessorDisposing", true); f.set("recreateRecorderRequested", false)
        f.service.requestAudioRecordRecreation()
        assertFalse(f.field("recreateRecorderRequested") as Boolean)
    }

}
