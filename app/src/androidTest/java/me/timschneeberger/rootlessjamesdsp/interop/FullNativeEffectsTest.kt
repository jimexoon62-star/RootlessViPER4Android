package me.timschneeberger.rootlessjamesdsp.interop

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.timschneeberger.rootlessjamesdsp.model.ProcessorMessage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.*

/** Numerical native-effect checks; not a claim about physical playback. */
@RunWith(AndroidJUnit4::class)
class FullNativeEffectsTest {
    private val callbacks = object : JamesDspWrapper.JamesDspCallbacks {
        override fun onLiveprogOutput(message: String) {}
        override fun onLiveprogExec(id: String) {}
        override fun onLiveprogResult(resultCode: Int, id: String, errorMessage: String?) {}
        override fun onVdcParseError() {}
        override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {}
    }
    private fun verify(name: String, configure: (Long) -> Boolean) {
        val handle = JamesDspWrapper.alloc(callbacks)
        assertNotEquals(0L, handle)
        try {
            JamesDspWrapper.setSamplingRate(handle, 48000f, true)
            JamesDspWrapper.setLimiterMode(handle, 2)
            assertTrue("$name configuration rejected", configure(handle))
            val input = FloatArray(960); val output = FloatArray(960)
            var frame = 0; var energy = 0.0; var difference = 0.0
            repeat(150) { block ->
                for(i in input.indices step 2) {
                    val t=frame++/48000.0
                    input[i]=(.025*sin(2*PI*60*t)+.03*sin(2*PI*997*t)+.02*sin(2*PI*7000*t)).toFloat()
                    input[i+1]=(.02*sin(2*PI*80*t)+.02*sin(2*PI*1499*t)).toFloat()
                }
                JamesDspWrapper.processFloat(handle,input,output,0,input.size)
                assertTrue("$name nonfinite output",output.all { it.isFinite() && abs(it)<4f })
                if(block>50) for(i in output.indices) { energy+=output[i]*output[i]; val d=output[i]-input[i]; difference+=d*d }
            }
            assertTrue("$name unexpectedly silent",energy>0.001)
            assertTrue("$name made no measurable change: $difference",difference>0.0001)
            android.util.Log.i("FullNativeEffectsTest","NATIVE_EFFECT name=$name differenceEnergy=$difference outputEnergy=$energy")
        } finally { JamesDspWrapper.free(handle) }
    }
    @Test fun viperBass()=verify("ViPER Bass") { JamesDspWrapper.setViperBass(it,true,0,76f,6f) }
    @Test fun viperClarity()=verify("ViPER Clarity") { JamesDspWrapper.setViperClarity(it,true,0,6f) }
    @Test fun differentialSurround()=verify("Differential surround") { JamesDspWrapper.setDiffSurround(it,true,5f,12f) }
    @Test fun fieldSurround()=verify("Field surround") { JamesDspWrapper.setFieldSurround(it,true,30f,50f) }
    @Test fun headphoneSurround()=verify("Headphone surround") { JamesDspWrapper.setHpSurround(it,true,60f,30f) }
    @Test fun cure()=verify("Cure") { JamesDspWrapper.setCure(it,true,1) }
    @Test fun reverb()=verify("Reverb") { JamesDspWrapper.setReverb(it,true,1) }
    @Test fun convolver()=verify("Convolver") {
        val impulse=FloatArray(256); impulse[0]=.5f; impulse[32]=.25f
        JamesDspWrapper.setConvolver(it,true,impulse,1,256)
    }
    @Test fun graphicEq()=verify("Graphic EQ") { JamesDspWrapper.setGraphicEq(it,true,"GraphicEQ: 20 -6; 100 -6; 1000 -6; 20000 -6") }
    @Test fun crossfeed()=verify("Crossfeed") { JamesDspWrapper.setCrossfeed(it,true,0,700,60) }
    @Test fun bassBoost()=verify("Bass boost") { JamesDspWrapper.setBassBoost(it,true,6f) }
    @Test fun stereoEnhancement()=verify("Stereo enhancement") { JamesDspWrapper.setStereoEnhancement(it,true,1.5f) }
    @Test fun vacuumTube()=verify("Vacuum tube") { JamesDspWrapper.setVacuumTube(it,true,2f) }
    @Test fun postGain()=verify("Post gain") { JamesDspWrapper.setPostGain(it,-12f) }
}
