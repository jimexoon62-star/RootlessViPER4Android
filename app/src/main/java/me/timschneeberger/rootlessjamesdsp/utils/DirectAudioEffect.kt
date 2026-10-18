package me.timschneeberger.rootlessjamesdsp.utils

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Handler
import android.os.Looper

/** Ordinary session insert effect. Never changes capture policy or original-stream volume. */
class DirectAudioEffect(session: Int, config: DirectEffectSettings, lost: () -> Unit) :
    DirectEffectRegistry.Effect<DirectEffectSettings> {
    private val effect = DynamicsProcessing(0, session, DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
        true, 15, false, 0, false, 0, true).build())
    private var closed = false
    init {
        try {
            update(config)
            check(effect.setEnabled(true) == AudioEffect.SUCCESS && effect.hasControl() && effect.enabled)
            // Never re-enable on control restoration. Retire on loss instead.
            val handler = Handler(Looper.getMainLooper())
            effect.setControlStatusListener { _, granted -> if (!granted) handler.post { lost() } }
            effect.setEnableStatusListener { _, enabled -> if (!enabled) handler.post { lost() } }
        } catch (e: Exception) { try { close() } catch (release: Exception) { e.addSuppressed(release) }; throw e }
    }
    @Synchronized override fun update(config: DirectEffectSettings) {
        check(!closed && effect.hasControl())
        val eq = DynamicsProcessing.Eq(true, config.eqEnabled, 15)
        config.gains.indices.forEach { eq.setBand(it, DynamicsProcessing.EqBand(true, config.cutoffs[it], config.gains[it])) }
        effect.setPreEqAllChannelsTo(eq)
        effect.setInputGainAllChannelsTo(config.inputGain)
        // Stereo-linked fixed safety limiter. Native ViPER limiter modes are not emulated.
        effect.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 20f, -0.1f, 0f))
        check(kotlin.math.abs(effect.getInputGainByChannelIndex(0) - config.inputGain) < 0.1f)
    }
    @Synchronized override fun healthy() = !closed && effect.hasControl() && effect.enabled
    @Synchronized override fun close() {
        if (closed) return
        closed = true
        var failure: Exception? = null
        try { effect.setControlStatusListener(null) } catch (e: Exception) { failure = e }
        try { effect.setEnableStatusListener(null) } catch (e: Exception) { failure = e }
        try { effect.setEnabled(false) } catch (e: Exception) { failure = e }
        var released = false
        repeat(2) { if (!released) try { effect.release(); released = true } catch (e: Exception) { failure = e } }
        check(released) { "Direct effect release unconfirmed: ${failure?.javaClass?.simpleName}" }
    }
}
