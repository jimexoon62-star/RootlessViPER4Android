package me.timschneeberger.rootlessjamesdsp.utils

import android.media.audiofx.AudioEffect
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import timber.log.Timber

class MutedAudioEffectReleaseTest {
    @Suppress("UNCHECKED_CAST")
    private fun owner(vararg effects: AudioEffect): MutedAudioEffectFactory {
        val factory = MutedAudioEffectFactory()
        val gate = factory.javaClass.getDeclaredField("ownership").apply { isAccessible = true }
            .get(factory) as MuteOwnership<AudioEffect>
        gate.resume(); effects.forEach { effect -> gate.create({ effect }, {}, {}) }
        return factory
    }
    @Test fun disableFailureCannotSkipNativeRelease() {
        val effect = mock(AudioEffect::class.java)
        `when`(effect.setEnabled(false)).thenThrow(IllegalStateException("injected disable failure"))
        owner(effect).suspendMuting()
        verify(effect).setEnableStatusListener(null)
        verify(effect).setControlStatusListener(null)
        verify(effect).release()
    }
    @Test fun releaseFailureHasExactlyOneRetry() {
        val effect = mock(AudioEffect::class.java)
        doThrow(IllegalStateException("injected release failure")).doNothing().`when`(effect).release()
        val factory = owner(effect)
        factory.suspendMuting(); factory.suspendMuting()
        verify(effect, times(2)).release()
    }
    @Test fun unreleasedMuteIsExplicitlyRecordedAndOtherEffectsStillRelease() {
        val messages = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) { messages.add(message) }
        }
        Timber.plant(tree)
        try {
            val effect = mock(AudioEffect::class.java)
            doThrow(IllegalStateException("injected persistent release failure")).`when`(effect).release()
            val other = mock(AudioEffect::class.java)
            owner(effect, other).suspendMuting()
            verify(effect, times(2)).release()
            verify(other).release()
            assertTrue(messages.any { it.contains("CRITICAL: mute release failed") })
        } finally { Timber.uproot(tree) }
    }
}
