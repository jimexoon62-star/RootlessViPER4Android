package me.timschneeberger.rootlessjamesdsp.interop

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.activity.MainActivity
import me.timschneeberger.rootlessjamesdsp.service.BaseAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.DirectAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.service.RootlessAudioProcessorService
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectServiceLifecycleTest {
    @Test fun repeatedEnableDisableAndBackendChangeLeaveNoDuplicateService() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences(Constants.PREF_APP,0)
        val previous=prefs.all[DirectAudioProcessorService.MODE_KEY]
        val previousLayout=prefs.all["v4a_classic_layout"]
        fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun count():Int { var result=0; onMain { result=BaseAudioProcessorService.activeServices }; return result }
        fun awaitCount(expected:Int) {
            val deadline=SystemClock.elapsedRealtime()+5000
            while(count()!=expected && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            assertEquals(expected,count())
        }
        try {
            onMain { prefs.edit().putBoolean("v4a_classic_layout",true).putBoolean(DirectAudioProcessorService.MODE_KEY,true).commit() }
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            SystemClock.sleep(1000)
            onMain {
                var unsupported=0; var power=0
                fun inspect(fragment: androidx.fragment.app.Fragment) {
                    if (fragment is me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment) {
                        val ns=fragment.preferenceManager.sharedPreferencesName
                        if (ns==Constants.PREF_OUTPUT) {
                            assertTrue("classic power control must remain usable",fragment.preferenceScreen.getPreference(0).isEnabled); power++
                        } else if (ns!=Constants.PREF_EQ) {
                            assertFalse("unsupported effect header must be disabled",fragment.preferenceScreen.getPreference(0).isEnabled); unsupported++
                        }
                    }
                    fragment.childFragmentManager.fragments.forEach(::inspect)
                }
                activity.supportFragmentManager.fragments.forEach(::inspect)
                assertTrue("no unsupported cards inspected",unsupported>0); assertTrue("no power control inspected",power>0)
            }
            onMain { RootlessAudioProcessorService.stop(context); prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,true).commit() }
            awaitCount(0)
            repeat(5) {
                onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
                onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
                onMain { RootlessAudioProcessorService.stop(context) }; awaitCount(0)
            }
            onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
            val bound=java.util.concurrent.CountDownLatch(1)
            var held: DirectAudioProcessorService?=null
            val connection=object : android.content.ServiceConnection {
                override fun onServiceConnected(name:android.content.ComponentName,binder:android.os.IBinder) {
                    held=(binder as BaseAudioProcessorService.LocalBinder).service as DirectAudioProcessorService
                    bound.countDown()
                }
                override fun onServiceDisconnected(name:android.content.ComponentName) {}
            }
            onMain { assertTrue(context.bindService(Intent(context,DirectAudioProcessorService::class.java),connection,android.content.Context.BIND_AUTO_CREATE)) }
            assertTrue(bound.await(5,java.util.concurrent.TimeUnit.SECONDS))
            try {
                onMain { RootlessAudioProcessorService.stop(context) }
                onMain { assertTrue("bound service retained processing",held!!.hasStoppedProcessing) }
                assertEquals("test must retain a real service binder",1,count())
            } finally { onMain { context.unbindService(connection) } }
            awaitCount(0)
            onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
            onMain { prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,false).commit() }
            awaitCount(0)
            onMain {
                var restored=0
                fun inspect(fragment: androidx.fragment.app.Fragment) {
                    if (fragment is me.timschneeberger.rootlessjamesdsp.fragment.PreferenceGroupFragment &&
                        fragment.preferenceManager.sharedPreferencesName==Constants.PREF_COMPANDER) {
                        fragment.javaClass.getDeclaredMethod("applyDirectCompatibilityControls").apply { isAccessible=true }.invoke(fragment)
                        val group=fragment.preferenceScreen.getPreference(0) as androidx.preference.PreferenceGroup
                        assertTrue("full-engine header was not restored",group.isEnabled)
                        assertTrue("full-engine child was not restored",group.getPreference(0).isEnabled)
                        restored++
                    }
                    fragment.childFragmentManager.fragments.forEach(::inspect)
                }
                activity.supportFragmentManager.fragments.forEach(::inspect)
                assertTrue("no full-engine controls restored",restored>0)
            }
        } finally {
            onMain {
                RootlessAudioProcessorService.stop(context)
                if(previous is Boolean) prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,previous).commit()
                else prefs.edit().remove(DirectAudioProcessorService.MODE_KEY).commit()
                if(previousLayout is Boolean) prefs.edit().putBoolean("v4a_classic_layout",previousLayout).commit()
                else prefs.edit().remove("v4a_classic_layout").commit()
            }
        }
    }
}
