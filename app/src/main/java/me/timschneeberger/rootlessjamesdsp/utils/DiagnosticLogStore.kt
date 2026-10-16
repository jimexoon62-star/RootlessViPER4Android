package me.timschneeberger.rootlessjamesdsp.utils

import java.io.File
import java.time.Instant

/** The exporter and writer share this store; every append is closed and visible. */
class DiagnosticLogStore(private val file: File, private val maxBytes: Long = 2_000_000L) {
    init { require(maxBytes >= 256); file.parentFile?.mkdirs(); if (!file.exists()) file.createNewFile() }
    @Synchronized fun append(message: String) {
        val entry = "${Instant.now()} ${message.take(8192)}\n"
        if (file.length() + entry.toByteArray(Charsets.UTF_8).size > maxBytes) {
            val tail = file.readText().takeLast((maxBytes / 8).toInt()).substringAfter('\n', "")
            file.writeText("LOG_ROTATED\n" + tail)
        }
        file.appendText(entry, Charsets.UTF_8)
    }
    @Synchronized fun snapshot(): String = file.readText(Charsets.UTF_8)
}
