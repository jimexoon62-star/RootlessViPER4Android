import android.content.Context;
import android.media.*;
import android.os.*;
import java.lang.reflect.*;

/** Disposable-emulator proof only. Routes one synthetic source UID, never owner media. */
public final class ShellCaptureProbe {
    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        return o.getClass().getMethod(name, types).invoke(o, args);
    }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("Real shell identity required");
        int sourceUid = Integer.parseInt(args[0]);
        int flags = Integer.parseInt(args[1]);
        if (sourceUid < 10000) throw new SecurityException("Synthetic application UID required");
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context context = system.createPackageContext("com.android.shell", 0);
        for (String p : new String[]{"CAPTURE_AUDIO_OUTPUT", "CAPTURE_MEDIA_OUTPUT", "MODIFY_AUDIO_ROUTING", "RECORD_AUDIO"})
            System.out.println("permission " + p + "=" + context.checkSelfPermission("android.permission." + p));
        Class<?> rb = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder");
        Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object ruleBuilder = rb.getConstructor().newInstance();
        call(ruleBuilder, "addMixRule", new Class[]{int.class, Object.class}, 4, Integer.valueOf(sourceUid));
        Object rule = call(ruleBuilder, "build", new Class[]{});
        Class<?> mb = Class.forName("android.media.audiopolicy.AudioMix$Builder");
        Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
        Object mixBuilder = mb.getConstructor(ruleType).newInstance(rule);
        AudioFormat format = new AudioFormat.Builder().setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        call(mixBuilder, "setFormat", new Class[]{AudioFormat.class}, format);
        call(mixBuilder, "setRouteFlags", new Class[]{int.class}, flags);
        Object mix = call(mixBuilder, "build", new Class[]{});
        Class<?> pb = Class.forName("android.media.audiopolicy.AudioPolicy$Builder");
        Class<?> policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object policyBuilder = pb.getConstructor(Context.class).newInstance(context);
        call(policyBuilder, "addMix", new Class[]{mixType}, mix);
        Object policy = call(policyBuilder, "build", new Class[]{});
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioRecord record = null;
        boolean registered = false;
        try {
            int result = (Integer) call(manager, "registerAudioPolicy", new Class[]{policyType}, policy);
            System.out.println("register=" + result + " routeFlags=" + flags);
            if (result != 0) throw new IllegalStateException("Policy rejected");
            registered = true;
            record = (AudioRecord) call(policy, "createAudioRecordSink", new Class[]{mixType}, mix);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Recorder unavailable");
            record.startRecording();
            short[] samples = new short[1920];
            long frames = 0, nonzero = 0, crossings = 0; double power = 0; int previous = 0;
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (SystemClock.elapsedRealtime() < deadline) {
                int n = record.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (n < 0 || (n & 1) != 0) throw new IllegalStateException("Read=" + n);
                if (n == 0) { Thread.sleep(5); continue; }
                for (int i = 0; i < n; i += 2) {
                    int value = samples[i];
                    power += (double)value * value;
                    if (value != 0) nonzero++;
                    if (previous < 0 && value >= 0) crossings++;
                    previous = value; frames++;
                }
            }
            double rms = frames == 0 ? 0 : Math.sqrt(power / frames) / 32768;
            double frequency = frames == 0 ? 0 : crossings * 48000.0 / frames;
            System.out.println("RESULT rate=" + record.getSampleRate() + " channels=" + record.getChannelCount()
                + " frames=" + frames + " nonzero=" + nonzero + " rms=" + rms + " frequency=" + frequency);
            boolean signal = rms > 0.01 && nonzero > 48000 && record.getSampleRate() == 48000 && record.getChannelCount() == 2;
            if (flags == 2 && !signal) throw new AssertionError("Full-quality routing proof failed");
            if (flags == 3 && signal) throw new AssertionError("Opt-out negative control unexpectedly captured");
        } finally {
            if (record != null) { try { record.stop(); } finally { record.release(); } }
            if (registered) call(manager, "unregisterAudioPolicy", new Class[]{policyType}, policy);
            System.out.println("CLEANUP complete");
        }
    }
}
