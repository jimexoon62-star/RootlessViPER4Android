package me.timschneeberger.rootlessjamesdsp.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One in-flight dump and one conflated follow-up; no event is lost just because
 * I/O is busy. A newer request invalidates older results before delivery. */
internal class SessionPollCoordinator<T>(
    scope: CoroutineScope,
    private val load: suspend () -> T,
    private val accept: (T) -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private val deliveryLock = Any()
    private val ioLock = Mutex()
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private var revision = 0L
    private var closed = false
    private var failures = 0
    private val worker = scope.launch { for (event in pending) pollNow() }

    fun request() = synchronized(deliveryLock) {
        if (!closed) {
            revision++
            pending.trySend(Unit)
        }
        Unit
    }

    suspend fun pollNow() = ioLock.withLock {
        val stamp = synchronized(deliveryLock) {
            if (closed) return@withLock
            revision
        }
        val value = try { load() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            synchronized(deliveryLock) {
                if (!closed && revision == stamp && failures < 3) {
                    failures++
                    onFailure(e)
                }
            }
            return@withLock
        }
        synchronized(deliveryLock) {
            if (!closed && revision == stamp) {
                failures = 0
                accept(value)
            }
        }
    }

    fun close() = synchronized(deliveryLock) {
        closed = true
        revision++
        pending.close()
        worker.cancel()
    }
}
