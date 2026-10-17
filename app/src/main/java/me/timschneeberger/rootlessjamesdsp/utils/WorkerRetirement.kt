package me.timschneeberger.rootlessjamesdsp.utils

/** Retire native state exactly once, after its last audio worker has left JNI. */
internal class WorkerRetirement(private val close: () -> Unit) {
    private var running = false
    private var retiring = false
    private var closed = false

    @Synchronized fun workerStarted() {
        check(!retiring && !running) { "Cannot start overlapping or retired worker" }
        running = true
    }

    @Synchronized fun workerFinished() {
        running = false
        closeIfReady()
    }

    @Synchronized fun retire() {
        retiring = true
        closeIfReady()
    }

    private fun closeIfReady() {
        if (retiring && !running && !closed) {
            closed = true
            close()
        }
    }
}
