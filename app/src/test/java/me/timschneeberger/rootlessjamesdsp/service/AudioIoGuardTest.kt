package me.timschneeberger.rootlessjamesdsp.service

import org.junit.Assert.*
import org.junit.Test

class AudioIoGuardTest {
    @Test fun partialReadIgnoresStaleTail() {
        val level = AudioIoGuard.measure(floatArrayOf(.5f, -.5f, Float.NaN, Float.NaN), 2)
        assertEquals(.5, level.rms, 1e-9)
        AudioIoGuard.validateRead(2, 4)
    }
    @Test fun partialWritesPreserveOffsets() {
        val offsets = mutableListOf<Int>()
        AudioIoGuard.writeFully(8, { false }) { offset, remaining ->
            offsets += offset
            minOf(2, remaining)
        }
        assertEquals(listOf(0, 2, 4, 6), offsets)
    }
    @Test fun shutdownStopsWrites() {
        AudioIoGuard.writeFully(8, { true }) { _, _ -> error("must not write") }
    }
    @Test fun zeroWriteCanRecover() {
        var calls = 0
        AudioIoGuard.writeFully(4, { false }) { _, remaining -> if (++calls == 1) 0 else remaining }
        assertEquals(2, calls)
    }
    @Test fun alignment() {
        assertEquals(24, AudioIoGuard.alignBytes(17, 8))
        assertEquals(16, AudioIoGuard.alignBytes(16, 8))
    }
    @Test fun int16StereoAndSilence() {
        val level = AudioIoGuard.measure(shortArrayOf(16384, -16384), 2)
        assertEquals(.5, level.rms, 1e-9)
        assertEquals(0.0, AudioIoGuard.measure(FloatArray(8), 8).peak, 0.0)
    }
    @Test(expected = IllegalStateException::class) fun negativeRead() = AudioIoGuard.validateRead(-6, 8)
    @Test(expected = IllegalStateException::class) fun oddRead() = AudioIoGuard.validateRead(3, 8)
    @Test(expected = IllegalStateException::class) fun oversizedRead() = AudioIoGuard.validateRead(10, 8)
    @Test(expected = IllegalStateException::class) fun failedWrite() = AudioIoGuard.writeFully(4, { false }) { _, _ -> -6 }
    @Test(expected = IllegalStateException::class) fun nonFiniteOutput() { AudioIoGuard.measure(floatArrayOf(Float.NaN, 0f), 2) }
}
