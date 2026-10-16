package me.timschneeberger.rootlessjamesdsp.utils

import java.io.IOException

/** Complete one interleaved stereo PCM block without processing an unread tail.
 * Nonblocking Android adapters supply the transfer; tests inject faults and time.
 */
internal object AudioTransfer {
    const val STALL_MS = 3000L

    fun complete(size: Int, cancelled: () -> Boolean, now: () -> Long,
                 waitForData: () -> Unit, io: (Int, Int) -> Int) {
        require(size > 0 && size % 2 == 0)
        var offset = 0
        var progressAt = now()
        while (offset < size) {
            if (cancelled()) throw InterruptedException("audio generation cancelled")
            val count = io(offset, size - offset)
            if (count < 0) throw IOException("Audio I/O error $count at $offset/$size")
            if (count > size - offset || count % 2 != 0)
                throw IOException("Unaligned audio transfer $count")
            if (count == 0) {
                if (now() - progressAt >= STALL_MS) throw IOException("Audio I/O stalled")
                waitForData()
            } else {
                offset += count
                progressAt = now()
            }
        }
    }
}
