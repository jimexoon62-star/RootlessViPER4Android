package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class WorkerRetirementTest {
    @Test fun delayedWorkerClosesAfterServiceDestroy() {
        var closed = 0
        val owner = WorkerRetirement { closed++ }
        owner.workerStarted()
        owner.retire()
        owner.retire()
        assertEquals(0, closed)
        owner.workerFinished()
        owner.retire()
        assertEquals(1, closed)
    }

    @Test fun completedWorkerCanRestartUntilRetired() {
        var closed = 0
        val owner = WorkerRetirement { closed++ }
        repeat(10) { owner.workerStarted(); owner.workerFinished() }
        assertEquals(0, closed)
        owner.retire()
        assertEquals(1, closed)
    }

    @Test fun simultaneousWorkerExitAndDestructionCloseOnce() {
        repeat(100) {
            val closed = AtomicInteger()
            val owner = WorkerRetirement { closed.incrementAndGet() }
            owner.workerStarted()
            val start = CountDownLatch(1)
            val worker = Thread { start.await(); owner.workerFinished() }
            val destroy = Thread { start.await(); owner.retire() }
            worker.start(); destroy.start(); start.countDown()
            worker.join(1000); destroy.join(1000)
            assertEquals(false, worker.isAlive || destroy.isAlive)
            assertEquals(1, closed.get())
        }
    }

    @Test(expected = IllegalStateException::class)
    fun retiredOwnerRejectsNewWorker() {
        val owner = WorkerRetirement {}
        owner.retire()
        owner.workerStarted()
    }
}
