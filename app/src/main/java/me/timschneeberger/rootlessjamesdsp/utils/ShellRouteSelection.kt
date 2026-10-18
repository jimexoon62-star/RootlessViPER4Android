package me.timschneeberger.rootlessjamesdsp.utils

import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry

/** Privileged routing is restricted to selected media owners with known capture flags. */
object ShellRouteSelection {
    fun select(sessions: Collection<AudioSessionDumpEntry>, ownUid: Int, ownPackage: String, blocked: Set<Int>, allowlist: Boolean): Set<Int> =
        sessions.groupBy { it.uid }.filter { (uid, entries) ->
            uid >= 10000 && uid != ownUid && entries.none { (it.captureFlags ?: 0) and 0x1000 != 0 } &&
                entries.any { it.packageName != ownPackage && it.isUsageRecordable() && it.captureFlags != null } &&
                (if (allowlist && blocked.isNotEmpty()) uid in blocked else allowlist || uid !in blocked)
        }.keys.toSet()
}
