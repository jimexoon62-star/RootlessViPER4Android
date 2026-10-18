package me.timschneeberger.rootlessjamesdsp.audio
import me.timschneeberger.rootlessjamesdsp.model.AudioSessionDumpEntry
import me.timschneeberger.rootlessjamesdsp.utils.ShellRouteSelection
import org.junit.Assert.*
import org.junit.Test
class ShellRouteSelectionTest {
    private fun entry(uid:Int=10042, flags:Int?=0x8600, usage:String="USAGE_MEDIA", pkg:String="synthetic")=AudioSessionDumpEntry(uid,pkg,usage,"MUSIC",flags)
    private fun select(vararg entries:AudioSessionDumpEntry, blocked:Set<Int> = emptySet(), allow:Boolean=false)=ShellRouteSelection.select(entries.toList(),10043,"viper",blocked,allow)
    @Test fun systemOnlyMediaIsAccepted() { assertEquals(setOf(10042),select(entry())) }
    @Test fun systemCaptureProhibitionRejectsEntireOwner() { assertTrue(select(entry(),entry(flags=0x9600)).isEmpty()) }
    @Test fun unknownFlagsCannotAuthorizeRouting() { assertTrue(select(entry(flags=null)).isEmpty()) }
    @Test fun ownOutputAndShellAreNeverRouted() { assertTrue(select(entry(uid=10043),entry(uid=2000),entry(pkg="viper")).isEmpty()) }
    @Test fun callsAndNotificationsAreNeverSelected() { assertTrue(select(entry(usage="USAGE_VOICE_COMMUNICATION"),entry(usage="USAGE_NOTIFICATION")).isEmpty()) }
    @Test fun blocklistAndAllowlistArePreserved() { assertTrue(select(entry(),blocked=setOf(10042)).isEmpty()); assertEquals(setOf(10042),select(entry(),blocked=setOf(10042),allow=true)); assertTrue(select(entry(),blocked=setOf(10044),allow=true).isEmpty()) }
    @Test fun sessionRemovalClearsSelection() { assertTrue(select().isEmpty()) }
    @Test fun newSessionOfExistingOwnerDoesNotDuplicatePipeline() { assertEquals(setOf(10042),select(entry(),entry())) }
}
