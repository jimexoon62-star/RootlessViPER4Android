package me.timschneeberger.rootlessjamesdsp.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AudioTransferTest {
    private fun transfer(vararg counts: Int): List<Pair<Int, Int>> {
        var index = 0
        var time = 0L
        val calls = mutableListOf<Pair<Int, Int>>()
        AudioTransfer.complete(8, { false }, { time }, { time += 1000 }) { offset, size ->
            calls.add(offset to size)
            counts[index++]
        }
        return calls
    }
    @Test fun normalBlock() { assertEquals(listOf(0 to 8), transfer(8)) }
    @Test fun partialReadsAndWritesPreserveOffsets() {
        assertEquals(listOf(0 to 8, 2 to 6, 6 to 2), transfer(2, 4, 2))
    }
    @Test fun temporaryZeroDoesNotProcessStaleTail() {
        assertEquals(listOf(0 to 8, 0 to 8, 2 to 6), transfer(0, 2, 6))
    }
    @Test(expected = IOException::class) fun negativeRead() { transfer(-6) }
    @Test(expected = IOException::class) fun negativeWriteAfterPartial() { transfer(2, -3) }
    @Test(expected = IOException::class) fun channelMisalignment() { transfer(3) }
    @Test(expected = IOException::class) fun invalidOversize() { transfer(10) }
    @Test(expected = IOException::class) fun producerOrConsumerStarvation() {
        transfer(0, 0, 0, 0)
    }
    @Test(expected = InterruptedException::class) fun shutdownCancelsTransfer() {
        AudioTransfer.complete(8, { true }, { 0L }, {}) { _, _ -> fail("I/O after cancellation"); 0 }
    }
    @Test fun silenceIsValidData() { assertEquals(listOf(0 to 8), transfer(8)) }
}
