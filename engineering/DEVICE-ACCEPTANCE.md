# Device acceptance record

HONOR BRP-NX1 / Android 16 / Spotify / soundcore R50i: NOT EXECUTED. ADB listed no connected devices in this workspace. No target installation or audible restoration time has been measured. The candidate is not accepted as a verified blackout fix.

## Controlled installation and rollback

The delivered candidate uses `com.alienware377.viper4android.rootless`, version code 130. This package cannot coexist with the original package as a separate app, preventing competing original/test services. Signing uses the repository test key; compare the certificate against the installed APK before update. Android rejects an incompatible signature without deleting data. Do not uninstall or clear app data to work around a signature mismatch.

Export profiles/settings using the existing app's export features first and retain the exact currently installed APK. Install the candidate as an update only if Android accepts its signature. This preserves application data and profiles. If a signature mismatch occurs, stop the installation path: a separately authorized compatible signing resource is required; no destructive workaround is supplied.

To stop testing, disable processing or force-stop this app. Settings/profile data remain. Returning to an older lower-version-code APK can be blocked by Android's downgrade policy. Do not uninstall to force rollback. A same-certificate rollback build with a higher version code and the previously installed source can restore prior code while retaining data; it must be built for the exact version retained by the owner. Immediate operational rollback is direct Spotify playback with processing disabled.

## Smallest owner-assisted acceptance session

1. Install the signed ARM64 candidate as an update; confirm version 4.0.3-blackout-test. Start Spotify on R50i and grant normal capture authorization. Confirm sound independently by listening or recording external output.
2. Play for at least 60 minutes. During that session perform skips/seeks, pause/resume, screen lock/unlock and background use. Record each action/time and any blackout.
3. Disconnect/reconnect Bluetooth, switch speaker/Bluetooth, and activate/deactivate DSP ten times. Confirm one working capture pipeline, audible output and retained settings after each transition.
4. Revoke capture from Android's screen-sharing control. Record a video/independent audio trace showing elapsed time from revocation to restored original playback. Confirm reauthorization is required.
5. For a deliberate worker fault, use the instrumentation fault test on a connected development device (not a process force-stop falsely presented as worker recovery). Verify original output returns, at most one retry occurs, and repeated failure leaves direct playback available. The opt-in TargetDeviceRecoveryTest instrumentation hook is implemented behind the non-exported in-process service binder; it requires `allowPlaybackFault=yes` and an already running authorized capture service. It has not been executed on the phone.
6. Immediately export Settings → Troubleshooting → audio session dump. It must include DIAGNOSTIC_START/READY, AUDIO_HEALTH and failure/recovery entries. Keep logs private. Record measured restoration time and audible corroboration alongside the action timestamp.

Acceptance requires all items above, including a deliberate worker fault and automatic session/capture recovery. Until then, hardware correctness is unverified. A native-only emulator test cannot substitute for these checks.

| Acceptance item | Result | Measured restoration |
|---|---|---|
| 60 min Spotify/R50i | Pending | — |
| Skip/seek/pause/resume | Pending | — |
| Lock/background | Pending | — |
| Bluetooth reconnect/speaker switch | Pending | — |
| Ten enable/disable cycles | Pending | — |
| Worker failure + bounded retry | Pending | — |
| Projection revocation | Pending | — |
| Session/capture recovery | Pending | — |
