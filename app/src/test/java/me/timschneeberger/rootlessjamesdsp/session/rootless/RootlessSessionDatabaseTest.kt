package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.Context
import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.utils.MutedAudioEffectFactory
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class RootlessSessionDatabaseTest {
    @Test fun fatalFailureReleasesAllAndRejectsLateSessions() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val database = RootlessSessionDatabase(mock(Context::class.java), factory)
        val effect = mock(AudioEffect::class.java)
        `when`(factory.make(100, "player")).thenReturn(effect)
        database.resumeMuting(); database.addSession(100, 1000, "player")
        assertEquals(1, database.sessionList.size)
        database.suspendMuting()
        verify(factory).suspendMuting()
        verify(factory).release(effect)
        database.addSession(101, 1000, "player")
        assertTrue(database.sessionList.isEmpty())
        verify(factory, never()).make(101, "player")
    }
    @Test fun sessionRemovalAndRepeatedShutdownAreIdempotent() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val database = RootlessSessionDatabase(mock(Context::class.java), factory)
        val effect = mock(AudioEffect::class.java)
        `when`(factory.make(100, "player")).thenReturn(effect)
        database.resumeMuting(); database.addSession(100, 1000, "player")
        database.removeSession(100); database.destroy(); database.destroy()
        database.resumeMuting(); database.addSession(100, 1000, "player")
        verify(factory, times(1)).make(100, "player")
        verify(factory, times(1)).release(effect)
        assertTrue(database.sessionList.isEmpty())
    }
    @Test fun duplicateSessionCannotLeakPreviousEffect() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val database = RootlessSessionDatabase(mock(Context::class.java), factory)
        `when`(factory.make(100, "player")).thenReturn(mock(AudioEffect::class.java))
        database.resumeMuting()
        repeat(10) { database.addSession(100, 1000, "player") }
        verify(factory, times(1)).make(100, "player")
        database.destroy()
    }
}
