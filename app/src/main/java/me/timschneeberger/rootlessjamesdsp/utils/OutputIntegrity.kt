package me.timschneeberger.rootlessjamesdsp.utils

import java.io.IOException
import kotlin.math.abs

/** Detects a dead DSP output, never treats silent input alone as a fault. */
internal class OutputIntegrity {
    private var silentOutputSeconds = 0.0
    var inputPeak = 0.0; private set
    var outputPeak = 0.0; private set

    fun check(input: FloatArray, output: FloatArray, rate: Int) {
        inputPeak = 0.0; outputPeak = 0.0
        for (i in input.indices) {
            if (!input[i].isFinite() || !output[i].isFinite()) throw IOException("Non-finite PCM")
            inputPeak = maxOf(inputPeak, abs(input[i].toDouble()))
            outputPeak = maxOf(outputPeak, abs(output[i].toDouble()))
        }
        checkPeaks(input.size, rate)
    }
    fun check(input: ShortArray, output: ShortArray, rate: Int) {
        inputPeak = 0.0; outputPeak = 0.0
        for (i in input.indices) {
            inputPeak = maxOf(inputPeak, abs(input[i].toDouble()) / 32768.0)
            outputPeak = maxOf(outputPeak, abs(output[i].toDouble()) / 32768.0)
        }
        checkPeaks(input.size, rate)
    }
    private fun checkPeaks(samples: Int, rate: Int) {
        silentOutputSeconds = if (inputPeak >= .001 && outputPeak == 0.0)
            silentOutputSeconds + samples / (2.0 * rate) else 0.0
        if (silentOutputSeconds >= 3.0) throw IOException("DSP produced digital zero for 3s of non-silent input")
    }
}
