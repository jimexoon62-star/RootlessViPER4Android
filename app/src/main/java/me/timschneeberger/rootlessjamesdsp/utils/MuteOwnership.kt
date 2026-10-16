package me.timschneeberger.rootlessjamesdsp.utils

import java.util.Collections
import java.util.IdentityHashMap

/** Serializes callback mutations against revocation, including callbacks already queued. */
internal class MuteOwnership<T : Any> {
    private val owned = Collections.newSetFromMap(IdentityHashMap<T, Boolean>())
    private var accepting = false

    @Synchronized fun resume() { accepting = true }
    @Synchronized fun <R> create(factory: () -> T?, configure: (T) -> R,
                                release: (T) -> Unit): T? {
        if (!accepting) return null
        val item = factory() ?: return null
        owned.add(item)
        try { configure(item) }
        catch (error: Throwable) {
            owned.remove(item)
            release(item)
            throw error
        }
        return item
    }
    @Synchronized fun mutate(item: T, action: () -> Unit) {
        if (accepting && item in owned) action()
    }
    @Synchronized fun contains(item: T) = accepting && item in owned
    @Synchronized fun release(item: T, action: (T) -> Unit) {
        if (owned.remove(item)) action(item)
    }
    @Synchronized fun suspendAll(release: (T) -> Unit) {
        accepting = false
        val retired = owned.toList()
        owned.clear()
        retired.forEach(release)
    }
}
