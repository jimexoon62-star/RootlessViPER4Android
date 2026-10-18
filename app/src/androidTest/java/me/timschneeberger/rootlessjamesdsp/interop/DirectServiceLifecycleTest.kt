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
        fun onMain(action:()->Unit)=instrumentation.runOnMainSync(action)
        fun count():Int { var result=0; onMain { result=BaseAudioProcessorService.activeServices }; return result }
        fun awaitCount(expected:Int) {
            val deadline=SystemClock.elapsedRealtime()+5000
            while(count()!=expected && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(50)
            assertEquals(expected,count())
        }
        try {
            context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SystemClock.sleep(1000)
            onMain { RootlessAudioProcessorService.stop(context); prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,true).commit() }
            awaitCount(0)
            repeat(5) {
                onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
                onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
                onMain { RootlessAudioProcessorService.stop(context) }; awaitCount(0)
            }
            onMain { RootlessAudioProcessorService.start(context,null) }; awaitCount(1)
            onMain { prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,false).commit() }
            awaitCount(0)
        } finally {
            onMain {
                RootlessAudioProcessorService.stop(context)
                if(previous is Boolean) prefs.edit().putBoolean(DirectAudioProcessorService.MODE_KEY,previous).commit()
                else prefs.edit().remove(DirectAudioProcessorService.MODE_KEY).commit()
            }
        }
    }
}
