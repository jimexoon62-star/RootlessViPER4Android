package me.timschneeberger.rootlessjamesdsp.audio;

/** This entry point exists only inside the instrumentation APK. */
public final class ShellGuardFaultWorker {
    private static void hang(String marker) {
        System.err.println(marker);
        while (true) { try { Thread.sleep(60000); } catch (InterruptedException ignored) {} }
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        ShellRoutingWorker.run(java.util.Arrays.copyOfRange(args, 1, args.length), System.in, System.out,
            () -> { if (mode.equals("init")) hang("TEST_CAPTURE_BLOCKED_AFTER_REGISTRATION"); },
            () -> { if (mode.equals("cleanup")) hang("TEST_POLICY_CLEANUP_BLOCKED"); });
    }
}
