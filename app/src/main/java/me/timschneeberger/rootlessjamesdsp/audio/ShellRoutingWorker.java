package me.timschneeberger.rootlessjamesdsp.audio;

import android.content.*;
import android.media.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;

/** Runs as the real, user-authorized shell UID. Owns routing, never a muting effect.
 * Lost renderer acknowledgements, EOF or process death restore original routing. */
public final class ShellRoutingWorker {
    public static final int MAGIC = 0x56345043;
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    public static void main(String[] args) {
        try { run(args, System.in, System.out); System.exit(0); }
        catch (Throwable failure) { System.err.println("SHELL_ROUTE_FAILED " + failure); System.exit(1); }
    }
    public static void run(String[] args, InputStream acknowledgements, OutputStream pcm) throws Exception {
        run(args, acknowledgements, pcm, () -> {}, () -> {});
    }
    /** Fault injection is provided only by the separate instrumentation APK's worker. */
    public static void run(String[] args, InputStream acknowledgements, OutputStream pcm,
                           Runnable beforeCapture, Runnable beforeCleanup) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("ADB shell authorization required");
        if (args.length < 2) throw new IllegalArgumentException("rate and selected source UIDs required");
        final int rate = Integer.parseInt(args[0]);
        final boolean monitor = args[1].equals("monitor");
        if (monitor && args.length < 3) throw new IllegalArgumentException("Monitor source UID required");
        if (monitor ? rate != 16000 : rate != 44100 && rate != 48000) throw new IllegalArgumentException("Unsupported rate");
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        else if (Looper.myLooper() == null) Looper.prepare();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context shellPackage = system.createPackageContext("com.android.shell", 0);
        Context context = new ContextWrapper(shellPackage) {
            @Override public String getOpPackageName() { return "com.android.shell"; }
            @Override public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();
            }
        };
        if (context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") != 0)
            throw new SecurityException("MODIFY_AUDIO_ROUTING unavailable");
        Class<?> ruleType = Class.forName("android.media.audiopolicy.AudioMixingRule");
        Object rules = Class.forName("android.media.audiopolicy.AudioMixingRule$Builder").getConstructor().newInstance();
        if (monitor) call(rules, "allowPrivilegedPlaybackCapture", new Class[]{boolean.class}, true);
        for (int i = monitor ? 2 : 1; i < args.length; i++) {
            int uid = Integer.parseInt(args[i]);
            if (uid < 10000) throw new SecurityException("Only selected media application UIDs may be routed");
            call(rules, "addMixRule", new Class[]{int.class, Object.class}, 4, Integer.valueOf(uid));
        }
        // Usage groups and UID groups are ANDed by AudioPolicy. Never route calls/microphones.
        for (int usage : new int[]{AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN})
            call(rules, "addRule", new Class[]{AudioAttributes.class, int.class}, new AudioAttributes.Builder().setUsage(usage).build(), 1);
        Object rule = call(rules, "build", new Class[]{});
        Class<?> mixType = Class.forName("android.media.audiopolicy.AudioMix");
        Object mixBuilder = Class.forName("android.media.audiopolicy.AudioMix$Builder").getConstructor(ruleType).newInstance(rule);
        AudioFormat format = new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(monitor ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build();
        call(mixBuilder, "setFormat", new Class[]{AudioFormat.class}, format);
        call(mixBuilder, "setRouteFlags", new Class[]{int.class}, monitor ? 3 : 2);
        Object mix = call(mixBuilder, "build", new Class[]{});
        Class<?> policyType = Class.forName("android.media.audiopolicy.AudioPolicy");
        Object builder = Class.forName("android.media.audiopolicy.AudioPolicy$Builder").getConstructor(Context.class).newInstance(context);
        call(builder, "addMix", new Class[]{mixType}, mix);
        Object policy = call(builder, "build", new Class[]{});
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        final AudioRecord[] recordRef = new AudioRecord[1];
        final boolean[] registered = {false};
        final Object lock = new Object();
        Runnable close = () -> {
            synchronized (lock) {
                beforeCleanup.run();
                // Restore direct playback FIRST, including when the writer is blocked.
                if (registered[0]) {
                    try { call(manager, "unregisterAudioPolicy", new Class[]{policyType}, policy); }
                    catch (Throwable e) { System.err.println("SHELL_ROUTE_RELEASE_FAILED " + e); }
                    registered[0] = false;
                }
                AudioRecord record = recordRef[0];
                if (record != null) {
                    try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
                    catch (Throwable e) { System.err.println("SHELL_RECORD_STOP_FAILED " + e); }
                    try { record.release(); } catch (Throwable e) { System.err.println("SHELL_RECORD_RELEASE_FAILED " + e); }
                }
                System.err.println("SHELL_ROUTE_RELEASED");
            }
        };
        ShellRouteGuard guard = new ShellRouteGuard(SystemClock::elapsedRealtime, close,
            task -> { Thread cleanup = new Thread(task, "ShellRouteCleanup"); cleanup.setDaemon(true); cleanup.start(); },
            code -> { System.err.println("SHELL_POLICY_OWNER_EXIT code=" + code); Runtime.getRuntime().halt(code); });
        Thread lease = new Thread(() -> {
            while (true) {
                guard.poll();
                try { Thread.sleep(25); } catch (InterruptedException ignored) { guard.close(1); }
            }
        }, "ShellRendererLease");
        lease.setDaemon(true); lease.start(); // BEFORE any policy registration or AudioRecord creation.
        Thread reader = new Thread(() -> {
            try { while (!guard.isClosed() && acknowledgements.read() != -1) guard.acknowledge(); }
            catch (IOException ignored) {} finally { guard.close(0); }
        }, "ShellRendererLeaseInput");
        reader.setDaemon(true); reader.start();
        try {
            synchronized (lock) {
                if (guard.isClosed()) throw new IOException("Renderer disconnected before registration");
                int result = (Integer) call(manager, "registerAudioPolicy", new Class[]{policyType}, policy);
                if (result != 0) throw new IOException("Policy registration=" + result);
                registered[0] = true;
                beforeCapture.run();
                if (guard.isClosed()) throw new IOException("Renderer disconnected during registration");
                AudioAttributes.Builder attributes = new AudioAttributes.Builder();
                call(attributes, "setInternalCapturePreset", new Class[]{int.class}, 8);
                call(attributes, "addTag", new Class[]{String.class}, "addr=" + call(mix, "getRegistration", new Class[]{}));
                AudioRecord.Builder rb = new AudioRecord.Builder();
                call(rb, "setContext", new Class[]{Context.class}, context);
                call(rb, "setAudioAttributes", new Class[]{AudioAttributes.class}, attributes.build());
                rb.setAudioFormat(new AudioFormat.Builder(format).setChannelMask(monitor ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO).build());
                rb.setBufferSizeInBytes(Math.max(19200, AudioRecord.getMinBufferSize(rate, monitor ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)));
                recordRef[0] = rb.build();
                if (recordRef[0].getSampleRate() != rate || recordRef[0].getChannelCount() != (monitor ? 1 : 2))
                    throw new IOException("Negotiated capture format differs from requested format");
                recordRef[0].startRecording();
            }
            guard.ready();
            DataOutputStream out = new DataOutputStream(pcm);
            out.writeInt(MAGIC); out.writeInt(rate); out.writeInt(monitor ? 1 : 2); out.flush();
            short[] samples = new short[960];
            byte[] bytes = new byte[samples.length * 2];
            while (!guard.isClosed()) {
                AudioRecord record = recordRef[0];
                if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Capture stopped");
                int n = record.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (n < 0 || (!monitor && (n & 1) != 0)) throw new IOException("Capture transfer=" + n);
                if (n == 0) { Thread.sleep(5); continue; }
                for (int i = 0; i < n; i++) { bytes[2*i] = (byte)samples[i]; bytes[2*i+1] = (byte)(samples[i] >> 8); }
                out.writeInt(n); out.writeLong(SystemClock.elapsedRealtime()); out.write(bytes, 0, n * 2); out.flush();
            }
        } catch (Throwable failure) {
            System.err.println("SHELL_ROUTE_FAILED " + failure); guard.close(1); throw failure;
        } finally {
            guard.close(0);
            // Keep main alive until cleanup or the independent hard deadline exits the process.
            // Neither a stuck Binder call nor a stuck recorder release can prevent this exit.
            lease.join();
        }
    }
}
