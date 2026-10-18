package me.timschneeberger.rootlessjamesdsp.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionPollCoordinatorTest {
    @Test fun sessionAppearingDuringDumpIsDeliveredWithoutAnotherPlaybackEvent() = runBlocking {
        val parent=SupervisorJob(); val scope=CoroutineScope(parent+Dispatchers.Default)
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val delivered=Channel<Int>(Channel.UNLIMITED); val state=AtomicInteger(1); val calls=AtomicInteger()
        val poll=SessionPollCoordinator(scope, {
            val snapshot=state.get()
            if(calls.incrementAndGet()==1) { started.complete(Unit); release.await() }
            snapshot
        }, { delivered.trySend(it); Unit }, { throw AssertionError(it) })
        try {
            withTimeout(5000) {
                poll.request(); started.await()
                state.set(2); poll.request(); release.complete(Unit)
                assertEquals(2,delivered.receive())
                assertEquals(2,calls.get()); assertTrue(delivered.tryReceive().isFailure)
            }
        } finally { poll.close(); parent.cancelAndJoin() }
    }

    @Test fun eventBurstSchedulesOneFollowUpAndUsesLatestProvider() = runBlocking {
        val parent=SupervisorJob(); val scope=CoroutineScope(parent+Dispatchers.Default)
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val delivered=Channel<Int>(Channel.UNLIMITED); val provider=AtomicInteger(0); val calls=AtomicInteger()
        val poll=SessionPollCoordinator(scope, {
            val snapshot=provider.get()
            if(calls.incrementAndGet()==1) { started.complete(Unit); release.await() }
            snapshot
        }, { delivered.trySend(it); Unit }, { throw AssertionError(it) })
        try {
            withTimeout(5000) {
                poll.request(); started.await()
                repeat(100) { provider.set(it+1); poll.request() }
                release.complete(Unit)
                assertEquals(100,delivered.receive()); assertEquals(2,calls.get())
                assertTrue(delivered.tryReceive().isFailure)
            }
        } finally { poll.close(); parent.cancelAndJoin() }
    }

    @Test fun shutdownDiscardsInFlightResultAndPendingEvents() = runBlocking {
        val parent=SupervisorJob(); val scope=CoroutineScope(parent+Dispatchers.Default)
        val started=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val deliveries=AtomicInteger(); val calls=AtomicInteger()
        val poll=SessionPollCoordinator(scope, {
            calls.incrementAndGet(); started.complete(Unit)
            // Model a native/dump read that finishes even after cancellation.
            withContext(NonCancellable) { release.await() }; 1
        }, { deliveries.incrementAndGet(); Unit }, { throw AssertionError(it) })
        try {
            withTimeout(5000) {
                poll.request(); started.await(); poll.request(); poll.close(); poll.request()
                release.complete(Unit); parent.cancelAndJoin()
                assertEquals(0,deliveries.get()); assertEquals(1,calls.get())
            }
        } finally { release.complete(Unit); poll.close(); parent.cancelAndJoin() }
    }

    @Test fun periodicAndEventReadsNeverOverlap() = runBlocking {
        val parent=SupervisorJob(); val scope=CoroutineScope(parent+Dispatchers.Default)
        val active=AtomicInteger(); val maximum=AtomicInteger(); val delivered=Channel<Int>(Channel.UNLIMITED)
        val poll=SessionPollCoordinator(scope, {
            val n=active.incrementAndGet(); maximum.updateAndGet { maxOf(it,n) }
            try { delay(20); 1 } finally { active.decrementAndGet() }
        }, { delivered.trySend(it); Unit }, { throw AssertionError(it) })
        try {
            withTimeout(5000) {
                val periodic=scope.launch { repeat(5) { poll.pollNow() } }
                repeat(100) { poll.request() }
                periodic.join(); delivered.receive()
                assertEquals(1,maximum.get())
            }
        } finally { poll.close(); parent.cancelAndJoin() }
    }

    @Test fun failedReadsAreBoundedAndNextSuccessfulEventStillWorks() = runBlocking {
        val parent=SupervisorJob(); val scope=CoroutineScope(parent+Dispatchers.Default)
        val reports=AtomicInteger(); val calls=AtomicInteger(); val delivered=Channel<Int>(Channel.UNLIMITED)
        val poll=SessionPollCoordinator(scope, {
            if(calls.incrementAndGet()<=5) error("injected dump failure")
            7
        }, { delivered.trySend(it); Unit }, { reports.incrementAndGet(); Unit })
        try {
            withTimeout(5000) {
                repeat(5) { poll.pollNow() }
                assertEquals(3,reports.get())
                poll.request(); assertEquals(7,delivered.receive())
                assertEquals(6,calls.get())
            }
        } finally { poll.close(); parent.cancelAndJoin() }
    }
}
