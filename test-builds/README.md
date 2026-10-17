# 4.0.5 native capture policy safety test

This candidate does not enable DSP processing for a source track that prohibits ordinary Android MediaProjection capture. It prevents suppression of such tracks, refuses startup suppression while capture consists solely of zeros, and closes native engine state after delayed worker termination.

Source runtime: 4b84a28. Package: com.alienware377.viper4android.rootless. Version code: 132. ARM64 only. Same signing identity as the previous test delivery; install as an update without deleting the application or clearing data. If Android rejects an update, do not uninstall to force it.

The owner's 4.0.4 trace shows native Spotify track attributes 0x8600, containing NO_MEDIA_PROJECTION (0x400), despite public AAudio player flags reporting zero. The source track lacks a capture secondary output; the capture output reports zero writes. This is distinct from the app's own lifecycle bugs, which are addressed separately. The trace does not establish which component supplied the native capture flag.

For this restricted session, direct original playback is the supported safe outcome. An APK cannot honestly be advertised as restoring ViPER processing under that restriction using the current ordinary projection architecture.

See engineering/NATIVE-CAPTURE-POLICY-FINDING.md for the reproducible Android source check and implementation details. 69 JVM tests passed with zero failures, errors, or skips. Actual APK signature, absence of testOnly, ZIP integrity, 16 KiB alignment, ARM64 ABI, native dependency closure, and mandatory DSP exports passed static checks. Build/test evidence and SHA256SUMS accompany the APK. Signing certificate SHA-256: dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675 (repository Android Debug signing identity). Android 16 emulator startup/update/native CI: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39013314754 (status must be checked; not counted as passed until completed). Physical HONOR/Spotify/Bluetooth processing and failure-recovery acceptance remains unexecuted for this revision.

Safe rollback of behavior: disable ViPER processing to retain direct playback and all settings. Do not uninstall or clear application data; an older release may be rejected as a version downgrade. Profile backup and a correctly signed later update are required before any package replacement that would remove data.
