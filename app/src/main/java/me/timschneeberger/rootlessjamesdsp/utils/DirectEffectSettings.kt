package me.timschneeberger.rootlessjamesdsp.utils

import kotlin.math.sqrt

data class DirectEffectSettings(val eqEnabled: Boolean, val cutoffs: List<Float>,
                                val gains: List<Float>, val inputGain: Float) {
    companion object {
        val centers = listOf(25f,40f,63f,100f,160f,250f,400f,630f,1000f,1600f,2500f,4000f,6300f,10000f,16000f)
        fun parse(enabled: Boolean, bands: String?, postGain: Float): DirectEffectSettings {
            require(postGain.isFinite())
            val values = bands?.split(';')?.map { it.toFloatOrNull() }
            val valid = values?.size == 30 && values.all { it != null && it.isFinite() } &&
                values.take(15).map { it!! }.let { f -> f.first() >= 10f && f.last() <= 20000f && f.zipWithNext().all { it.first < it.second } }
            val frequencies = if (valid) values!!.take(15).map { it!! } else centers
            val gains = if (valid) values!!.drop(15).map { it!!.coerceIn(-15f,15f) } else List(15) { 0f }
            val headroom = if (enabled) gains.maxOrNull()!!.coerceAtLeast(0f) else 0f
            val cutoffs = frequencies.zipWithNext().map { sqrt(it.first * it.second) } + 20000f
            return DirectEffectSettings(enabled, cutoffs, gains, (postGain.coerceIn(-15f,15f) - headroom).coerceIn(-30f,15f))
        }
    }
}
