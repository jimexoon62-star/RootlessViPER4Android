# 4.0.6 signal-status engineering test build

This revision adds signal/API-evidence status to the main toolbar and service notification. It distinguishes a waiting pipeline, a source capture restriction, nonzero captured/processed signal with owned sessions and advancing output, and stopped processing with original playback requested. It does not claim independently verified audible sound or promise Spotify capture when the native track opts out.

ARM64 APK: RootlessViPER4Android-4.0.6-signal-status-arm64.apk
Application ID: com.alienware377.viper4android.rootless
Version code: 133
Runtime source: 6aada4c
Signing identity: repository Android Debug certificate; SHA-256 dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675.

Install as an update to the previous same-signature build. Do not uninstall or clear application data. The separate synthetic capture probe is not packaged in this APK and is not required on the owner's device.

74 JVM tests passed with no failures/errors/skips. Signature, normal-install manifest gate (testOnly absent/false), ZIP integrity, 16 KiB APK alignment, ARM64 dependency closure, mandatory DSP exports, and embedded source revision passed static verification. See evidence.json and junit/ for exact results. Android 16 x86_64 final capture/native run succeeded: 5 native regressions plus 1 captured-signal integration test. Allowed/system-only/allowed-again capture RMS was 0.0640319393 / 0 / 0.0640314638, with 335872 returned samples in every stage. The actual captured PCM passed stereo-frequency and +6 dB JNI-gain assertions, then replacement output-head progress. CI: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39072145337 . Startup/update checks passed before fixture failures in earlier runs; the final capture-only run did not repeat startup. The delivered ARM64 APK and executed x86_64 emulator are distinct ABI artifacts. Physical HONOR/Spotify/audible acceptance remains unexecuted. See engineering/SIGNAL-CAPTURE-VALIDATION.md, including recorded failures and corrections.

## Small owner-assisted acceptance gate

1. Install the update, preserve profiles, and authorize capture through the normal system prompt. Keep only one ViPER capture service active; this APK updates the same package.
2. Play Spotify through the usual Bluetooth route for one minute and record the exact status shown beneath the title/in the notification. A capture restriction status means the source is bypassed; do not infer DSP activity from the power toggle.
3. At a comfortable low listening level, use Output gain/Post gain at -12 dB and compare processing enabled vs disabled. Record whether loudness changes clearly and whether any silence occurs. Restore the original setting afterward. A lower gain is used to avoid introducing clipping.

Stop if playback becomes silent and disable processing to request direct playback; record the time and export the new diagnostic log. API-level output progress is not a substitute for this audible comparison. The 60-minute, Bluetooth-switching and deliberate-failure acceptance suite follows only after the short gate passes.

## Rollback

Disable processing to retain direct playback and existing settings. Do not clear application data. Installing a lower version code may be rejected by Android; no destructive downgrade/uninstall is prescribed. A package replacement requires profile backup and compatible signing/versioning.
