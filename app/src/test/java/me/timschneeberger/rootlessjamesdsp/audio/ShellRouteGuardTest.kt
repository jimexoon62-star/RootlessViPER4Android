package me.timschneeberger.rootlessjamesdsp.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ShellRouteGuardTest {
    private class Harness {
        var time = 100L
        val cleanup = mutableListOf<Runnable>()
        val exits = mutableListOf<Int>()
        val releases = AtomicInteger()
        val guard = ShellRouteGuard({ time }, { releases.incrementAndGet() }, { cleanup.add(it) }, { exits.add(it) })
    }
    @Test fun initializationTimeoutDoesNotDependOnCaptureCreationReturning() {
        val h=Harness(); h.time+=3999; h.guard.poll(); assertFalse(h.guard.isClosed)
        h.time++; h.guard.poll(); assertTrue(h.guard.isClosed); assertEquals(1,h.cleanup.size)
        // Registration/creation holds the cleanup lock indefinitely: no cleanup can execute.
        h.time+=249; h.guard.poll(); assertTrue(h.exits.isEmpty())
        h.time++; h.guard.poll(); assertEquals(listOf(1),h.exits)
    }
    @Test fun blockedCleanupCannotPreventPolicyOwnerTermination() {
        val h=Harness(); h.guard.ready(); h.guard.close(0)
        h.time+=250; h.guard.poll(); assertEquals(listOf(0),h.exits)
        assertEquals(0,h.releases.get())
    }
    @Test fun normalCleanupCompletesOnceWithoutWaitingForHardDeadline() {
        val h=Harness(); h.guard.close(0); h.guard.close(1)
        assertEquals(1,h.cleanup.size); h.cleanup.single().run(); h.guard.poll(); h.guard.poll()
        assertEquals(1,h.releases.get()); assertEquals(listOf(0),h.exits)
    }
    @Test fun leaseStartsWhenCaptureIsReadyAndRequiresRendererProgress() {
        val h=Harness(); h.time+=3500; h.guard.ready(); h.time+=1499; h.guard.poll()
        assertFalse(h.guard.isClosed); h.guard.acknowledge(); h.time+=1499; h.guard.poll()
        assertFalse(h.guard.isClosed); h.time++; h.guard.poll(); assertTrue(h.guard.isClosed)
    }
    @Test fun staleAcknowledgementsCannotResurrectRetiredGeneration() {
        val h=Harness(); h.guard.ready(); h.guard.close(0)
        repeat(10) { h.guard.acknowledge(); h.guard.ready() }
        h.time+=250; h.guard.poll(); assertEquals(listOf(0),h.exits)
    }
    @Test fun acknowledgementsCannotExtendUnfinishedInitialization() {
        val h=Harness(); h.time+=3000; h.guard.acknowledge(); h.time+=1000; h.guard.poll()
        assertTrue(h.guard.isClosed)
    }
}
