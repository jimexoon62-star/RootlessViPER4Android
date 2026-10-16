package me.timschneeberger.rootlessjamesdsp.utils

/** Silent input is not an error. It simply stops authorizing source suppression. */
internal class CaptureSignalLease {
    private var silentSeconds = 0.0
    fun update(silent: Boolean, seconds: Double): Boolean {
        silentSeconds = if (silent) silentSeconds + seconds else 0.0
        return silentSeconds < 2.0
    }
}
