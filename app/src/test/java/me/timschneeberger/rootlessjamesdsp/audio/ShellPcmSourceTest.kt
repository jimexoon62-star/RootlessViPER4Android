package me.timschneeberger.rootlessjamesdsp.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ShellPcmSourceTest {
    private fun packet(n: Int = 4, payload: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(ShellRoutingWorker.MAGIC); out.writeInt(48000); out.writeInt(2); out.writeInt(n)
            if (payload) repeat(maxOf(0, n)) { out.writeByte((it * 1234) and 255); out.writeByte((it * 1234) shr 8) }
        }
        return bytes.toByteArray()
    }
    @Test fun normalLifecyclePreservesCountStereoAndTail() {
        val ack = ByteArrayOutputStream(); var released = 0
        val source = ShellPcmSource.connected(ByteArrayInputStream(packet()), ack) { released++ }
        source.begin(48000)
        val out = ShortArray(8) { 999 }
        assertEquals(4, source.read(out)); assertEquals(3702.toShort(), out[3]); assertEquals(999.toShort(), out[4])
        assertEquals(0, ack.size()); source.rendered(); assertEquals(1, ack.size())
        source.close(); source.close(); assertEquals(1, released)
    }
    @Test fun partialTransportReadsAreReassembled() {
        val stream = object : ByteArrayInputStream(packet()) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(len, 1))
        }
        val source = ShellPcmSource.connected(stream, ByteArrayOutputStream()) {}
        source.begin(48000); val out = ShortArray(960)
        assertEquals(4, source.read(out)); assertEquals(2468.toShort(), out[2]); source.close()
    }
    @Test fun zeroNegativeOddAndOversizedPacketsFailWithoutAck() {
        for (count in listOf(0, -3, 3, 962)) {
            val ack = ByteArrayOutputStream()
            val source = ShellPcmSource.connected(ByteArrayInputStream(packet(count, false)), ack) {}
            source.begin(48000)
            try { source.read(ShortArray(960)); fail("Invalid packet=$count") } catch (_: IOException) {}
            assertEquals(0, ack.size()); source.close()
        }
    }
    @Test fun truncatedPayloadAndLostProducerFail() {
        for (bytes in listOf(packet().copyOf(18), packet().copyOf(12))) {
            val source = ShellPcmSource.connected(ByteArrayInputStream(bytes), ByteArrayOutputStream()) {}
            source.begin(48000)
            try { source.read(ShortArray(960)); fail("EOF must fail") } catch (_: EOFException) {}
            source.close()
        }
    }
    @Test fun mismatchedRateIsNeverAccepted() {
        val source = ShellPcmSource.connected(ByteArrayInputStream(packet()), ByteArrayOutputStream()) {}
        try { source.begin(44100); fail("Rate mismatch") } catch (_: IOException) {} finally { source.close() }
    }
    @Test fun shutdownPreventsFurtherLeaseRenewal() {
        val ack = ByteArrayOutputStream(); val source = ShellPcmSource.connected(ByteArrayInputStream(packet()), ack) {}
        source.close()
        try { source.rendered(); fail("Retired generation") } catch (_: IOException) {}
        assertEquals(0, ack.size())
    }
    @Test fun concurrentShutdownReleasesTransportExactlyOnce() {
        val releases=java.util.concurrent.atomic.AtomicInteger()
        val source=ShellPcmSource.connected(ByteArrayInputStream(packet()),ByteArrayOutputStream()) { releases.incrementAndGet(); Unit }
        val pool=java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val tasks=(0 until 20).map { pool.submit { source.close() } }
            tasks.forEach { it.get(2,java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(1,releases.get())
        } finally { pool.shutdownNow() }
    }

}
