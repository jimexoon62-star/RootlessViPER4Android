package me.timschneeberger.rootlessjamesdsp.utils

/** Empty snapshots and repeated session IDs cannot reset a failed owner's budget forever. */
internal class SourceRearmGate {
    private var lastOwner = emptySet<Int>()
    private var used = false
    fun observe(owners: Set<Int>): Boolean {
        if (owners.isEmpty() || owners == lastOwner) return false
        lastOwner = owners.toSet(); used = false
        return true
    }
    fun newSession(): Boolean {
        if (used) return false
        used = true; return true
    }
    fun confirmedHealthy() { used = false }
}
