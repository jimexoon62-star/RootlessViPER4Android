package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MuteOwnershipTest {
    private class Effect { var enabled = false; var releases = 0 }
    private fun create(gate: MuteOwnership<Effect>): Effect? =
        gate.create({ Effect() }, { it.enabled = true }, { it.releases++; it.enabled = false })
    private fun release(effect: Effect) { effect.releases++; effect.enabled = false }

    @Test fun noMuteBeforeOutputReady() { assertNull(create(MuteOwnership())) }
    @Test fun normalLifecycleAndIdempotentShutdown() {
        val gate = MuteOwnership<Effect>(); gate.resume()
        val effect = create(gate)!!
        assertTrue(effect.enabled)
        gate.suspendAll(::release); gate.suspendAll(::release)
        assertEquals(1, effect.releases); assertFalse(effect.enabled)
        assertNull(create(gate))
    }
    @Test fun queuedCallbackCannotReenableReleasedEffect() {
        val gate = MuteOwnership<Effect>(); gate.resume(); val effect = create(gate)!!
        gate.suspendAll(::release)
        gate.mutate(effect) { effect.enabled = true }
        gate.resume() // old callbacks also cannot affect a new generation
        gate.mutate(effect) { effect.enabled = true }
        assertFalse(effect.enabled)
    }
    @Test fun sessionRemovalReleasesOnlyOnce() {
        val gate = MuteOwnership<Effect>(); gate.resume(); val effect = create(gate)!!
        gate.release(effect, ::release); gate.suspendAll(::release)
        assertEquals(1, effect.releases)
    }
    @Test fun failedAttachmentReleasesConstructedEffect() {
        val gate = MuteOwnership<Effect>(); gate.resume(); val effect = Effect()
        try {
            gate.create({ effect }, { it.enabled = true; error("injected") }, ::release)
            fail("exception expected")
        } catch (_: IllegalStateException) { }
        assertEquals(1, effect.releases); assertFalse(effect.enabled)
    }
    @Test fun simultaneousShutdownAndCallbacksCannotReinstateMute() {
        repeat(100) {
            val gate = MuteOwnership<Effect>(); gate.resume(); val effect = create(gate)!!
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val callback = Thread { start.await(); repeat(100) { gate.mutate(effect) { effect.enabled = true } }; done.countDown() }
            val shutdown = Thread { start.await(); gate.suspendAll(::release); done.countDown() }
            callback.start(); shutdown.start(); start.countDown()
            assertTrue("deadlock", done.await(2, TimeUnit.SECONDS))
            assertFalse(effect.enabled); assertEquals(1, effect.releases)
        }
    }
    @Test fun repeatedEnableDisableAndLateCallbacks() {
        val gate = MuteOwnership<Effect>()
        repeat(100) {
            gate.resume(); val effect = create(gate)!!
            gate.suspendAll(::release); gate.mutate(effect) { effect.enabled = true }
            assertEquals(1, effect.releases); assertFalse(effect.enabled)
        }
    }
}
