package org.rv4a.captureprobe;
import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import org.json.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.*;

/** A bounded self-capture probe. No mute effects, external media or native engine. */
public class ProbeService extends Service {
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final AtomicBoolean started=new AtomicBoolean();
    public IBinder onBind(Intent i) { return null; }
    public int onStartCommand(Intent intent,int flags,int id) {
        if (!started.compareAndSet(false,true)) return START_NOT_STICKY;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("probe","Synthetic capture test",NotificationManager.IMPORTANCE_LOW));
        startForeground(1,new Notification.Builder(this,"probe").setContentTitle("Synthetic capture test running").setSmallIcon(android.R.drawable.ic_media_play).build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        Intent grant=intent.getParcelableExtra("grant",Intent.class);
        new Thread(()->runProbe(grant),"CaptureProbe").start();
        return START_NOT_STICKY;
    }
    private void runProbe(Intent grant) {
        MediaProjection projection=null; AudioRecord recorder=null;
        JSONObject result=new JSONObject();
        try {
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK,grant);
            projection.registerCallback(new MediaProjection.Callback(){public void onStop(){stopping.set(true);}},new Handler(getMainLooper()));
            AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUid(android.os.Process.myUid()).build();
            recorder=new AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                .setBufferSizeInBytes(32768).build();
            recorder.startRecording();
            result.put("rate",48000);
            result.put("allowed",measure(recorder,AudioAttributes.ALLOW_CAPTURE_BY_ALL));
            result.put("systemOnly",measure(recorder,AudioAttributes.ALLOW_CAPTURE_BY_SYSTEM));
            result.put("allowedAgain",measure(recorder,AudioAttributes.ALLOW_CAPTURE_BY_ALL));
            result.put("complete",true);
        } catch(Throwable e) {
            try{result.put("error",e.toString());}catch(Exception ignored){}
        } finally {
            if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();}
            if(projection!=null)projection.stop();
            try{Files.write(getFilesDir().toPath().resolve("result.json"),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){android.util.Log.e("CaptureProbe","Cannot save result",e);}
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        }
    }
    private AudioFormat format() { return new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(); }
    private JSONObject measure(AudioRecord recorder,int policy) throws Exception {
        // INPUT and OUTPUT stereo channel masks differ: use input mask for recorder.
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setAllowedCapturePolicy(policy).build())
            .setAudioFormat(format()).setBufferSizeInBytes(32768).setTransferMode(AudioTrack.MODE_STREAM).build();
        AtomicBoolean done=new AtomicBoolean(); AtomicBoolean writerFailed=new AtomicBoolean();
        Thread writer=new Thread(()->{
            float[] tone=new float[960]; long frame=0; long progress=SystemClock.elapsedRealtime();
            try {
                track.play();
                while(!done.get()&&!stopping.get()) {
                    for(int i=0;i<tone.length;i+=2,frame++) {
                        tone[i]=(float)(0.1*Math.sin(2*Math.PI*997*frame/48000));
                        tone[i+1]=(float)(0.08*Math.sin(2*Math.PI*1499*frame/48000));
                    }
                    int offset=0;
                    while(offset<tone.length&&!done.get()&&!stopping.get()) {
                        int n=track.write(tone,offset,tone.length-offset,AudioTrack.WRITE_NON_BLOCKING);
                        if(n<0||n%2!=0)throw new IllegalStateException("tone write "+n);
                        if(n>0){offset+=n;progress=SystemClock.elapsedRealtime();}
                        else {if(SystemClock.elapsedRealtime()-progress>2000)throw new IllegalStateException("tone stalled");Thread.sleep(3);}
                    }
                }
            }catch(Exception e){if(!done.get()&&!stopping.get()){writerFailed.set(true);android.util.Log.e("CaptureProbe","Tone writer failed",e);}}
        },"ProbeTone");
        JSONObject stage=new JSONObject(); JSONArray sample=new JSONArray();
        long start=SystemClock.elapsedRealtime(), count=0; double squares=0,peak=0;
        try {
            writer.start();float[] input=new float[2048];
            while(SystemClock.elapsedRealtime()-start<5000&&!stopping.get()) {
                int n=recorder.read(input,0,input.length,AudioRecord.READ_NON_BLOCKING);
                if(n<0||n%2!=0)throw new IllegalStateException("capture read "+n);
                if(SystemClock.elapsedRealtime()-start>1500) {
                    for(int i=0;i<n;i++){squares+=input[i]*input[i];peak=Math.max(peak,Math.abs(input[i]));count++;}
                    if(sample.length()==0&&n>=512&&peak>0.01)for(int i=0;i<512;i++)sample.put(input[i]);
                }
                if(n==0)Thread.sleep(3);
            }
            stage.put("samples",count).put("peak",peak).put("rms",count==0?0:Math.sqrt(squares/count)).put("pcm",sample).put("head",track.getPlaybackHeadPosition()).put("policy",policy);
        }finally{
            done.set(true);writer.interrupt();writer.join(2500);
            if(writer.isAlive())throw new IllegalStateException("tone worker did not exit");
            track.pause();track.flush();track.release();
        }
        if(writerFailed.get())throw new IllegalStateException("tone writer failed; see CaptureProbe log");
        if(stopping.get())throw new IllegalStateException("projection revoked during probe");
        return stage;
    }
}
