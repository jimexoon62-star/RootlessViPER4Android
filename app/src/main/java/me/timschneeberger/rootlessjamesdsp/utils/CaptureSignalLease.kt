package me.timschneeberger.rootlessjamesdsp.utils

/** Silent input is not an error. It simply stops authorizing source suppression. */
internal class CaptureSignalLease {
    private var silentSeconds = 0.0
    private var observedSignal = false
    fun update(silent: Boolean, seconds: Double): Boolean {
        if (!silent) observedSignal = true
        silentSeconds = if (silent) silentSeconds + seconds else 0.0
        return observedSignal && silentSeconds < 2.0
    }
}
