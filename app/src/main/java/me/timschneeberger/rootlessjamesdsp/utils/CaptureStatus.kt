package me.timschneeberger.rootlessjamesdsp.utils

/** API-level signal/progress evidence; never a claim of independently heard output. */
enum class CaptureStatus {
    CHECKING, SIGNAL_PROGRESS, RESTRICTED, DIRECT;
    companion object {
        const val ACTION_CHANGED = "me.timschneeberger.rootlessjamesdsp.CAPTURE_STATUS_CHANGED"
        fun resolve(failed: Boolean, restricted: Boolean, signal: Boolean,
                    ownsSessions: Boolean, outputAdvancing: Boolean): CaptureStatus = when {
            failed -> DIRECT
            signal && ownsSessions && outputAdvancing -> SIGNAL_PROGRESS
            restricted && !ownsSessions -> RESTRICTED
            else -> CHECKING
        }
    }
}
