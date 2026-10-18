package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class DirectEffectRegistryTest {
    private class Fake : DirectEffectRegistry.Effect<Int> {
        var closed = 0; var updates = 0; var bad = false; var alive = true; var releaseFails = false
        override fun update(config: Int) { if (bad) error("DSP"); updates++ }
        override fun healthy() = alive
        override fun close() { closed++; if (releaseFails) error("release") }
    }
    @Test fun lifecycleAndPartialRemoval() {
        val effects = mutableListOf<Fake>()
        val r = DirectEffectRegistry<Int>({ _,_,_ -> Fake().also { effects += it } }, {})
        r.reconcile(mapOf(1 to 100,2 to 200),0); assertEquals(2,r.controlledCount())
        r.update(3); assertEquals(1,effects[0].updates)
        r.reconcile(mapOf(2 to 200),0); assertEquals(1,effects[0].closed)
        r.close(); r.close(); r.reconcile(mapOf(3 to 300),0)
        assertEquals(listOf(1,1), effects.map { it.closed }); assertEquals(0,r.controlledCount())
    }
    @Test fun failedAttachIsBoundedUntilIdentityRemoved() {
        var attempts = 0
        val r = DirectEffectRegistry<Int>({ _,_,_ -> attempts++; error("unavailable") }, {})
        repeat(100) { r.reconcile(mapOf(1 to 100),0) }; assertEquals(1,attempts)
        r.reconcile(emptyMap(),0); r.reconcile(mapOf(1 to 100),0); assertEquals(2,attempts)
    }
    @Test fun controlCallbackDoesNotReviveOrRetireReplacement() {
        val callbacks = mutableListOf<() -> Unit>(); val effects = mutableListOf<Fake>()
        val r = DirectEffectRegistry<Int>({ _,_,lost -> callbacks += lost; Fake().also { effects += it } }, {})
        r.reconcile(mapOf(1 to 100),0); callbacks[0](); callbacks[0]()
        assertEquals(1,effects[0].closed); assertEquals(0,r.controlledCount())
        r.reconcile(mapOf(1 to 101),0); callbacks[0]()
        assertEquals(1,r.controlledCount()); assertEquals(0,effects[1].closed)
        r.close(); callbacks[1](); assertEquals(1,effects[1].closed)
    }
    @Test fun parameterFailureReleasesAndQuarantines() {
        val f=Fake(); val r=DirectEffectRegistry<Int>({ _,_,_ -> f }, {})
        r.reconcile(mapOf(1 to 100),0); f.bad=true; r.update(4); r.update(5)
        assertEquals(1,f.closed); assertEquals(0,r.controlledCount())
    }
    @Test fun disabledEffectDetectedWithoutCallbacks() {
        val f=Fake(); val r=DirectEffectRegistry<Int>({ _,_,_ -> f }, {})
        r.reconcile(mapOf(1 to 100),0); f.alive=false; r.checkHealth(); r.checkHealth()
        assertEquals(1,f.closed); assertEquals(0,r.controlledCount())
    }
    @Test fun releaseFailureIsRecordedAndOtherEffectsAreReleased() {
        val log=mutableListOf<String>(); val effects=mutableListOf<Fake>()
        val r=DirectEffectRegistry<Int>({ _,_,_ -> Fake().also { effects+=it } }, {log+=it})
        r.reconcile(mapOf(1 to 100,2 to 200),0); effects[0].releaseFails=true; r.close()
        assertTrue(log.any { it.contains("CRITICAL") }); assertEquals(listOf(1,1),effects.map {it.closed})
    }
    @Test fun shutdownRacingListenerCannotLeakOrRestart() {
        repeat(100) {
            val f=Fake(); lateinit var callback: () -> Unit
            val r=DirectEffectRegistry<Int>({ _,_,lost -> callback=lost; f }, {})
            r.reconcile(mapOf(1 to 100),0)
            val start=CountDownLatch(1)
            val a=Thread { start.await(); r.close() }; val b=Thread {start.await(); callback()}
            a.start(); b.start(); start.countDown(); a.join(1000); b.join(1000)
            assertFalse(a.isAlive || b.isAlive); assertEquals(1,f.closed); assertEquals(0,r.controlledCount())
        }
    }
    @Test fun synchronousLossDuringConstructionClosesReturnedResource() {
        val f=Fake(); val r=DirectEffectRegistry<Int>({ _,_,lost -> lost(); f }, {})
        r.reconcile(mapOf(1 to 100),0); assertEquals(1,f.closed); assertEquals(0,r.controlledCount())
    }
    @Test fun globalSessionNeverAttached() {
        val r=DirectEffectRegistry<Int>({ _,_,_ -> error("must not attach") }, {})
        r.reconcile(mapOf(0 to 100),0); assertEquals(0,r.controlledCount())
    }
    @Test fun synchronousLossReleaseFailureIsExplicit() {
        val messages=mutableListOf<String>(); val f=Fake().also {it.releaseFails=true}
        val r=DirectEffectRegistry<Int>({ _,_,lost -> lost(); f }, {messages+=it})
        r.reconcile(mapOf(1 to 100),0)
        assertEquals(1,f.closed); assertTrue(messages.any {it.contains("CRITICAL release unconfirmed")})
    }
    @Test fun constructorCleanupFailureIsExplicit() {
        val messages=mutableListOf<String>()
        val r=DirectEffectRegistry<Int>({ _,_,_ ->
            throw IllegalStateException("constructor").also {it.addSuppressed(IllegalStateException("release unconfirmed"))}
        }, {messages+=it})
        r.reconcile(mapOf(1 to 100),0)
        assertTrue(messages.any {it.contains("CRITICAL constructor cleanup unconfirmed")})
    }
}
