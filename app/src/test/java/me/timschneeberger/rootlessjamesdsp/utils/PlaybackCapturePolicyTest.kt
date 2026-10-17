package me.timschneeberger.rootlessjamesdsp.utils

import android.content.Context
import android.media.audiofx.AudioEffect
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.session.rootless.RootlessSessionDatabase
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class PlaybackCapturePolicyTest {
    // Normalized native-policy structure from the owner's capture. No personal data.
    private val native = """
        1. Port ID: 33; Session ID: 321; uid 12345; State: Active
           AUDIO_FORMAT_PCM_16_BIT; 44100; Channel mask: 0x3
           Attributes: { Content type: AUDIO_CONTENT_TYPE_MUSIC Usage: AUDIO_USAGE_MEDIA Source: AUDIO_SOURCE_DEFAULT Flags: 0x8600 Tags:  }
           Stream: 3; Flags: 00000008; Refcount: 1; InternalMute: No
           DAP Primary Mix: 0x0
        2. Port ID: 20; Session ID: 322; uid 12346; State: Inactive
           Attributes: { Content type: AUDIO_CONTENT_TYPE_UNKNOWN Usage: AUDIO_USAGE_MEDIA Flags: 0xa00 Tags: }
           DAP Secondary Outputs: 141,
    """.trimIndent()

    @Test fun nativeRestrictionExistsDespitePublicPlayerFlagsBeingZero() {
        val flags = PlaybackCapturePolicy.parseNativeFlags(native)
        assertEquals(0x8600, flags[321 to 12345])
        assertTrue(PlaybackCapturePolicy.isRestricted(flags[321 to 12345]))
        assertFalse(PlaybackCapturePolicy.isRestricted(flags[322 to 12346]))
        assertFalse(PlaybackCapturePolicy.isRestricted(0))
    }
    @Test fun bothNativeCaptureRestrictionsAreRecognized() {
        assertTrue(PlaybackCapturePolicy.isRestricted(0x400))
        assertTrue(PlaybackCapturePolicy.isRestricted(0x1000))
        assertFalse(PlaybackCapturePolicy.isRestricted(null))
        assertFalse(PlaybackCapturePolicy.isRestricted(0x8000))
    }
    @Test fun duplicateActiveRowsCannotEraseRestriction() {
        val duplicate = "\nSession ID: 321; uid 12345; State: Active\nAttributes: { Flags: 0x0 }"
        assertEquals(0x8600, PlaybackCapturePolicy.parseNativeFlags(native + duplicate)[321 to 12345])
    }
    @Test fun malformedFlagsCannotBleedIntoNextClient() {
        val input = "Session ID: 1; uid 10;\nAttributes: { Flags: garbage }\n" +
            "Session ID: 2; uid 20;\nAttributes: { Flags: 0x400 }"
        val result = PlaybackCapturePolicy.parseNativeFlags(input)
        assertNull(result[1 to 10])
        assertEquals(0x400, result[2 to 20])
    }
    @Test fun restrictedSessionCannotCreateMuteEvenWithPublicDiscoveryAndResume() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val db = RootlessSessionDatabase(mock(Context::class.java), factory)
        val entry = AudioSessionDumpEntry(12345, "player", "USAGE_MEDIA", "MUSIC", 0x8600)
        db.updateCaptureRestrictions(mapOf(321 to entry))
        repeat(10) {
            db.resumeMuting()
            db.addSession(321, 12345, "player")
            db.suspendMuting()
        }
        verify(factory, never()).make(321, "player")
        assertTrue(db.sessionList.isEmpty())
    }
    @Test fun newNativeRestrictionReleasesExistingMuteAndBlocksLateCallbacks() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val effect = mock(AudioEffect::class.java)
        `when`(factory.make(321, "player")).thenReturn(effect)
        val db = RootlessSessionDatabase(mock(Context::class.java), factory)
        db.resumeMuting(); db.addSession(321, 12345, "player")
        db.updateCaptureRestrictions(mapOf(321 to AudioSessionDumpEntry(12345, "player", "USAGE_MEDIA", "MUSIC", 0x400)))
        verify(factory).release(effect)
        db.addSession(321, 12345, "player")
        verify(factory, times(1)).make(321, "player")
        assertTrue(db.sessionList.isEmpty())
    }
    @Test fun legitimatelyChangedNativePolicyAllowsNewSessionAgain() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val db = RootlessSessionDatabase(mock(Context::class.java), factory)
        val blocked = AudioSessionDumpEntry(12345, "player", "USAGE_MEDIA", "MUSIC", 0x400)
        db.updateCaptureRestrictions(mapOf(321 to blocked))
        db.resumeMuting(); db.addSession(321, 12345, "player")
        verify(factory, never()).make(321, "player")
        db.updateCaptureRestrictions(mapOf(321 to blocked.copy(captureFlags = 0)))
        db.addSession(321, 12345, "player")
        verify(factory).make(321, "player")
    }

    @Test fun ownOutputCaptureOptOutDoesNotReportAnotherSourceAsBlocked() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("viper")
        val db = RootlessSessionDatabase(context, mock(MutedAudioEffectFactory::class.java))
        db.updateCaptureRestrictions(mapOf(1 to AudioSessionDumpEntry(12345,"viper","USAGE_MEDIA","MUSIC",0x1c00)))
        assertFalse(db.hasCaptureRestrictions())
        db.updateCaptureRestrictions(mapOf(2 to AudioSessionDumpEntry(12346,"player","USAGE_MEDIA","MUSIC",0x400)))
        assertTrue(db.hasCaptureRestrictions())
    }

    @Test fun changedSessionOwnerDoesNotInheritAnotherAppsRestriction() {
        val factory = mock(MutedAudioEffectFactory::class.java)
        val db = RootlessSessionDatabase(mock(Context::class.java), factory)
        db.updateCaptureRestrictions(mapOf(321 to AudioSessionDumpEntry(12345, "blocked", "USAGE_MEDIA", "MUSIC", 0x400)))
        db.resumeMuting(); db.addSession(321, 12346, "other")
        verify(factory).make(321, "other")
    }
}
