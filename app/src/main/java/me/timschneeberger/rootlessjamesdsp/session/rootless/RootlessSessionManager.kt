package me.timschneeberger.rootlessjamesdsp.session.rootless

import android.content.*
import me.timschneeberger.rootlessjamesdsp.session.dump.DumpManager
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionInfoDump
import me.timschneeberger.rootlessjamesdsp.session.dump.data.ISessionPolicyInfoDump
import me.timschneeberger.rootlessjamesdsp.session.shared.BaseSessionManager


class RootlessSessionManager(context: Context) : BaseSessionManager(context)
{
    // Session database
    val sessionDatabase: RootlessSessionDatabase = RootlessSessionDatabase(context)
    // Session policy database
    val sessionPolicyDatabase: SessionRecordingPolicyManager = SessionRecordingPolicyManager(context)

    override fun destroy()
    {
        super.destroy()
        sessionDatabase.destroy()
        sessionPolicyDatabase.destroy()
    }

    override fun handleSessionDump(sessionDump: ISessionInfoDump?) {
        // Public AudioPlaybackConfiguration flags can differ from the native
        // policy attributes (notably AAudio). Never use public zero flags to
        // override an actual NO_MEDIA_PROJECTION restriction.
        val policy = sessionDump as? me.timschneeberger.rootlessjamesdsp.session.dump.data.AudioPolicyServiceDump
            ?: dumpManager.dumpCaptureAllowlistLog()
        policy?.let {
            sessionPolicyDatabase.update(it)
            sessionDatabase.updateCaptureRestrictions(it.sessions)
        }

        sessionDump?.let { sessionDatabase.update(it) }
    }

    override fun onDumpMethodChange(method: DumpManager.Method) {
        sessionDatabase.clearSessions()
        sessionPolicyDatabase.clearSessions()
        super.onDumpMethodChange(method)
    }
}