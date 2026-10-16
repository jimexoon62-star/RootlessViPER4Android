package me.timschneeberger.rootlessjamesdsp.utils

/** Initial routing is expected for every new track, not a reason to rebuild it. */
internal class RouteChangeDetector {
    private var last: Int? = null
    fun changed(device: Int?): Boolean {
        if (device == null) return false
        val previous = last
        last = device
        return previous != null && previous != device
    }
}
