package me.timschneeberger.rootlessjamesdsp.utils

/** Lifetime budget: a new explicit user activation is required after exhaustion. */
internal class RecoveryBudget(private val limit: Int = 1) {
    private var attempts = 0
    @Synchronized fun take(): Boolean {
        if (attempts >= limit) return false
        attempts++
        return true
    }
}
