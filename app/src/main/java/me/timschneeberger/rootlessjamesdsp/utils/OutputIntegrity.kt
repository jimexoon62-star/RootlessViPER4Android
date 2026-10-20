package me.timschneeberger.rootlessjamesdsp.utils

import java.io.IOException
import kotlin.math.abs

/** Detects a dead DSP output, never treats silent input alone as a fault. */
internal class OutputIntegrity {
    private var silentOutputSeconds = 0.0
    var inputPeak = 0.0; private set
    var outputPeak = 0.0; private set

    fun check(input: FloatArray, output: FloatArray, rate: Int, samples: Int = input.size) {
        require(rate > 0 && samples >= 0 && samples % 2 == 0 && samples <= input.size && samples <= output.size)
        inputPeak = 0.0; outputPeak = 0.0
        for (i in 0 until samples) {
            if (!input[i].isFinite() || !output[i].isFinite()) throw IOException("Non-finite PCM")
            inputPeak = maxOf(inputPeak, abs(input[i].toDouble()))
            outputPeak = maxOf(outputPeak, abs(output[i].toDouble()))
        }
        checkPeaks(samples, rate)
    }
    fun check(input: ShortArray, output: ShortArray, rate: Int, samples: Int = input.size) {
        require(rate > 0 && samples >= 0 && samples % 2 == 0 && samples <= input.size && samples <= output.size)
        inputPeak = 0.0; outputPeak = 0.0
        for (i in 0 until samples) {
            inputPeak = maxOf(inputPeak, abs(input[i].toDouble()) / 32768.0)
            outputPeak = maxOf(outputPeak, abs(output[i].toDouble()) / 32768.0)
        }
        checkPeaks(samples, rate)
    }
    private fun checkPeaks(samples: Int, rate: Int) {
        silentOutputSeconds = if (inputPeak >= .001 && outputPeak == 0.0)
            silentOutputSeconds + samples / (2.0 * rate) else 0.0
        if (silentOutputSeconds >= 3.0) throw IOException("DSP produced digital zero for 3s of non-silent input")
    }
}
