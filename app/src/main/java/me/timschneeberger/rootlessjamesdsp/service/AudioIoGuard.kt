package me.timschneeberger.rootlessjamesdsp.service

import kotlin.math.abs
import kotlin.math.sqrt

/** Counts are interleaved stereo samples, not bytes or frames. */
internal object AudioIoGuard {
    data class Level(val rms: Double, val peak: Double)
    fun validateRead(count: Int, capacity: Int) {
        check(count >= 0) { "AudioRecord.read failed: $count" }
        check(count <= capacity && count % 2 == 0) { "Invalid stereo sample count: $count/$capacity" }
    }
    fun alignBytes(bytes: Int, frameBytes: Int): Int {
        require(bytes > 0 && frameBytes > 0)
        return Math.multiplyExact(Math.addExact(bytes, frameBytes - 1) / frameBytes, frameBytes)
    }
    fun measure(samples: FloatArray, count: Int): Level {
        require(count in 1..samples.size)
        var sum = 0.0
        var peak = 0.0
        for (i in 0 until count) {
            val v = samples[i].toDouble()
            check(v.isFinite()) { "Non-finite DSP/PCM sample at $i" }
            sum += v * v
            peak = maxOf(peak, abs(v))
        }
        return Level(sqrt(sum / count), peak)
    }
    fun measure(samples: ShortArray, count: Int): Level {
        require(count in 1..samples.size)
        var sum = 0.0
        var peak = 0.0
        for (i in 0 until count) {
            val v = samples[i] / 32768.0
            sum += v * v
            peak = maxOf(peak, abs(v))
        }
        return Level(sqrt(sum / count), peak)
    }
    fun writeFully(count: Int, stopping: () -> Boolean, write: (Int, Int) -> Int) {
        require(count > 0 && count % 2 == 0)
        var offset = 0
        var lastProgress = System.nanoTime()
        while (offset < count && !stopping()) {
            val written = write(offset, count - offset)
            check(written >= 0 && written <= count - offset && written % 2 == 0) {
                "AudioTrack.write failed or invalid count: $written"
            }
            if (written == 0) {
                check(System.nanoTime() - lastProgress < 1_000_000_000L) { "AudioTrack.write stalled" }
                Thread.sleep(2)
            } else {
                offset += written
                lastProgress = System.nanoTime()
            }
        }
    }
}
