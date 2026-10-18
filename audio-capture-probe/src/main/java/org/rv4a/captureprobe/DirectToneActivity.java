package org.rv4a.captureprobe;

import android.app.Activity;
import android.media.*;
import android.os.*;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate-UID opt-out source for the direct-service integration test only. */
public class DirectToneActivity extends Activity {
    private final AtomicBoolean stopped = new AtomicBoolean();
    private Thread writer;
    private AudioTrack track;
    private boolean nativeTone;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private static native int startNative(int frequency);
    private static native void stopNative();
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Synthetic opted-out tone; automatically stops after 90 seconds.");
        setContentView(label);
        if(getIntent().getBooleanExtra("aaudio",false)) {
            System.loadLibrary("direct_tone");
            int result=startNative(getIntent().getIntExtra("frequency",997));
            if(result!=0) throw new IllegalStateException("AAudio source start failed: "+result);
            nativeTone=true;
            handler.postDelayed(this::finish,90000);
            return;
        }
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
            .setBufferSizeInBytes(32768).setTransferMode(AudioTrack.MODE_STREAM).build();
        writer = new Thread(() -> {
            try {
                track.play();
                int frequency=getIntent().getIntExtra("frequency",997);
                if(frequency<100 || frequency>10000) throw new IllegalArgumentException("frequency");
                long frame=0, progress=SystemClock.elapsedRealtime(), deadline=progress+90000;
                float[] pcm=new float[960];
                while(!stopped.get() && SystemClock.elapsedRealtime()<deadline) {
                    for(int i=0;i<pcm.length;i+=2) {
                        float v=(float)(.2*Math.sin(2*Math.PI*frequency*frame++/48000));
                        pcm[i]=v; pcm[i+1]=v;
                    }
                    int offset=0;
                    while(offset<pcm.length && !stopped.get()) {
                        int n=track.write(pcm,offset,pcm.length-offset,AudioTrack.WRITE_NON_BLOCKING);
                        if(n<0 || n%2!=0) throw new IllegalStateException("tone write "+n);
                        if(n>0) { offset+=n; progress=SystemClock.elapsedRealtime(); }
                        else { if(SystemClock.elapsedRealtime()-progress>3000) throw new IllegalStateException("tone stalled"); Thread.sleep(2); }
                    }
                }
            } catch(Exception e) {
                if(!stopped.get()) android.util.Log.e("DirectTone","writer failed",e);
            } finally { track.stop(); track.release(); }
        },"SeparateUidTone");
        writer.start();
    }
    @Override protected void onDestroy() {
        stopped.set(true);
        handler.removeCallbacksAndMessages(null);
        if(nativeTone) { stopNative(); nativeTone=false; }
        if(writer!=null) { writer.interrupt(); try { writer.join(1000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
        super.onDestroy();
    }
}
