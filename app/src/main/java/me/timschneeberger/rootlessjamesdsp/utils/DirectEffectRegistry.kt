package me.timschneeberger.rootlessjamesdsp.utils

/** No suppression, capture or replacement playback is involved. Failed identities
 * are quarantined until removal; callbacks cannot revive a retired effect. */
class DirectEffectRegistry<C>(private val factory: (Int, C, () -> Unit) -> Effect<C>,
                              private val report: (String) -> Unit) {
    interface Effect<C> { fun update(config: C); fun healthy(): Boolean = true; fun close() }
    private class Entry<C>(val uid: Int) { var effect: Effect<C>? = null; var failed = false }
    private val entries = mutableMapOf<Int, Entry<C>>()
    private var closed = false
    @Synchronized fun reconcile(sessions: Map<Int, Int>, config: C) {
        if (closed) return
        entries.keys.toList().filter { sessions[it] != entries[it]?.uid }.forEach { retire(it) }
        sessions.filterKeys { it > 0 }.forEach { (sid, uid) ->
            if (entries.containsKey(sid)) return@forEach
            val entry = Entry<C>(uid)
            entries[sid] = entry
            try {
                val effect = factory(sid, config) { fail(sid, entry) }
                if (closed || entry.failed || entries[sid] !== entry) effect.close()
                else entry.effect = effect
            } catch (e: Exception) { fail(sid, entry); report("attach failed: ${e.javaClass.simpleName}") }
        }
    }
    @Synchronized fun update(config: C) {
        if (closed) return
        entries.toMap().forEach { (sid, entry) ->
            try { entry.effect?.update(config) }
            catch (e: Exception) { fail(sid, entry); report("update failed: ${e.javaClass.simpleName}") }
        }
    }
    @Synchronized private fun fail(sid: Int, entry: Entry<C>) {
        if (entries[sid] !== entry || entry.failed) return
        entry.failed = true
        val effect = entry.effect; entry.effect = null
        try { effect?.close() } catch (e: Exception) { report("CRITICAL release unconfirmed: ${e.javaClass.simpleName}") }
        report("Direct effect retired; original playback remains available")
    }
    private fun retire(sid: Int) {
        val entry = entries[sid] ?: return
        fail(sid, entry); entries.remove(sid)
    }
    @Synchronized fun checkHealth() {
        if (closed) return
        entries.toMap().forEach { (sid, entry) ->
            try { if (entry.effect?.healthy() == false) fail(sid, entry) }
            catch (e: Exception) { fail(sid, entry) }
        }
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        entries.keys.toList().forEach { retire(it) }
    }
    @Synchronized fun controlledCount() = entries.values.count { it.effect != null && !it.failed }
}
