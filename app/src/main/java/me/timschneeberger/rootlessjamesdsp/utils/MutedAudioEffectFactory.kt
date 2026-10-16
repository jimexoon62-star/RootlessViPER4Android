package me.timschneeberger.rootlessjamesdsp.utils

import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import android.media.audiofx.DynamicsProcessing
import android.os.Build.VERSION_CODES
import dev.rikka.tools.refine.Refine
import timber.log.Timber
import java.util.*

class MutedAudioEffectFactory {
    private val ownership = MuteOwnership<AudioEffect>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var sessionLossListener: ((sid: Int, packageName: String) -> Unit)? = null

    fun resume() = ownership.resume()
    fun suspendMuting() = ownership.suspendAll(::releaseNative)
    fun release(effect: AudioEffect) = ownership.release(effect, ::releaseNative)

    private fun releaseNative(effect: AudioEffect) {
        try { effect.setEnableStatusListener(null) }
        catch (ex: Exception) { Timber.e(ex, "Mute enable listener detach failed") }
        try { effect.setControlStatusListener(null) }
        catch (ex: Exception) { Timber.e(ex, "Mute control listener detach failed") }
        try {
            val status = effect.setEnabled(false)
            if (status != AudioEffect.SUCCESS) Timber.e("Mute disable status=%d; attempting release", status)
        } catch (ex: Exception) { Timber.e(ex, "Mute disable failed; attempting release") }
        // One immediate retry only; cleanup cannot enter an unbounded loop.
        repeat(2) { attempt ->
            try { effect.release(); Timber.i("MUTE_RELEASED native effect release returned"); return }
            catch (ex: Exception) {
                Timber.e(ex, "Mute release failed (attempt %d/2)", attempt + 1)
            }
        }
        Timber.e("CRITICAL: mute release failed after both attempts; native release unconfirmed")
    }

    fun make(sid: Int, packageName: String): AudioEffect? {
        if (!isDeviceCompatible()) return null
        for ((type, uuid) in supportedTypeUuids) {
            if (!AudioEffectHidden.isEffectTypeAvailable(uuid)) continue
            try { return make(type, sid, packageName) }
            catch (ex: Exception) { Timber.w(ex, "Mute attachment failed (%s)", type.name) }
        }
        return null
    }

    fun make(type: MuteEffects, sid: Int, packageName: String): AudioEffect? {
        return ownership.create({
            when (type) {
                MuteEffects.DynamicsProcessing -> DynamicsProcessing(Int.MAX_VALUE, sid, null)
                MuteEffects.Volume -> Refine.unsafeCast<AudioEffect>(AudioEffectHidden(
                    volumeHiddenTypeUuid, AudioEffectHidden.EFFECT_TYPE_NULL, Int.MAX_VALUE, sid))
            }
        }, { muteEffect ->
            when (type) {
                MuteEffects.DynamicsProcessing ->
                    (muteEffect as DynamicsProcessing).setInputGainAllChannelsTo(-200f)
                MuteEffects.Volume -> {
                    val hidden = Refine.unsafeCast<AudioEffectHidden>(muteEffect)
                    hidden.setParameter(VolumeParams.MUTE.ordinal, 1)
                    hidden.setParameter(VolumeParams.LEVEL.ordinal, -96)
                }
            }
            muteEffect.enabled = true
            fun reportLoss(effect: AudioEffect) {
                // Never enter the database while holding the ownership monitor.
                handler.post {
                    if (ownership.contains(effect)) sessionLossListener?.invoke(sid, packageName)
                }
            }
            muteEffect.setEnableStatusListener { effect, enabled ->
                ownership.mutate(effect) {
                    if (!enabled) {
                        // A disabled effect is a pipeline fault. Do not fight teardown
                        // or a competing owner by repeatedly re-enabling muting.
                        reportLoss(effect)
                    }
                }
            }
            muteEffect.setControlStatusListener { effect, granted ->
                ownership.mutate(effect) {
                    if (!granted) reportLoss(effect)
                    // Regaining control never implicitly re-applies suppression.
                }
            }
        }, ::releaseNative)
    }

    companion object {
        enum class MuteEffects {
            DynamicsProcessing,
            Volume
        }

        enum class VolumeParams {
            LEVEL,                 // type SLmillibel = typedef SLuint16 (set & get)
            MAXLEVEL,              // type SLmillibel = typedef SLuint16 (get)
            MUTE,                  // type SLboolean  = typedef SLuint32 (set & get)
            ENABLESTEREOPOSITION,  // type SLboolean  = typedef SLuint32 (set & get)
            STEREOPOSITION,        // type SLpermille = typedef SLuint16 (set & get)
        }

        private val volumeHiddenTypeUuid = UUID.fromString("09e8ede0-ddde-11db-b4f6-0002a5d5c51b")
        private val dynamicsProcessingTypeUuid = sdkAbove(VERSION_CODES.P) {
            AudioEffect.EFFECT_TYPE_DYNAMICS_PROCESSING
        }.below {
            UUID.fromString("00000000-0000-0000-0000-000000000000")
        }

        private val supportedTypeUuids = mapOf(
            MuteEffects.DynamicsProcessing to dynamicsProcessingTypeUuid,
            MuteEffects.Volume to volumeHiddenTypeUuid,
        )

        fun isDeviceCompatible(): Boolean {
            var isCompatible = false
            supportedTypeUuids.forEach {
                if(AudioEffectHidden.isEffectTypeAvailable(it.value)) {
                    isCompatible = true
                    Timber.i("isDeviceCompatible: device supports ${it.key}")
                }
            }
            return isCompatible
        }
    }
}