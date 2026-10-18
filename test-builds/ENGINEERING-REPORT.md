# RootlessViPER4Android 4.0.7 — controlled compatibility test

Runtime source: [777e121](https://github.com/jimexoon62-star/RootlessViPER4Android/commit/777e121). Review branch: `fix/rootless-audio-blackout-honor-android16` in the owner's authorized fork. Upstream and unrelated clinic repositories were not modified.

## Finding and scope

The latest HONOR 4.0.6 capture has eight zero input/output peak samples while I/O frame counters advance. Spotify has native NO_MEDIA_PROJECTION, no capture secondary output, and a zero-write capture sink. This explains the absent capture-based effect in that recording. It does not prove the original October 16 blackout's precise initiating event, an AAudio/MMAP incompatibility, or an OEM power-management cause.

The full ViPER engine cannot process source samples it does not receive. This build therefore adds a clearly labelled, exclusive **Android DynamicsProcessing compatibility backend: graphic EQ and gain only**, using the original session without recording, projection, original-stream suppression, replacement AudioTrack or audio focus takeover. It is not the complete native ViPER engine. Unsupported controls are disabled without changing their saved values. Automatic positive-EQ headroom and a fixed stereo-linked safety limiter apply; phase/interpolation and the native advanced effects are not reproduced. The full previous native engine and required libraries remain available by disabling compatibility in Troubleshooting.

Direct mode does not request microphone/projection/overlay setup. Existing DUMP access is used to discover sessions; existing app selection filters remain in force. Capture policy flags are never changed. Direct mode is the default for this experimental build.

## Safety changes

The previous full-backend fail-open, checked nonblocking I/O, signal lease, native capture restriction, bounded recovery and mute-ownership protections remain. This change also explicitly stops both backends before relying on service destruction: Android can retain a service while a client holds a BIND_AUTO_CREATE binding; MainActivity also must clear stale binder references. Stopped UI binders are detached. Terminal failure notifies the UI to unbind; initial capture startup failure no longer cancels its bounded recovery immediately. Queued capture starts reject a retired backend selection.

Direct effects are keyed by session and UID, released on removal/identity change/shutdown, and quarantined after creation/update/control failure. Listener callbacks never re-enable them. A one-second API control check detects disabled effects even if a callback is missing. Release failures, including constructor cancellation cleanup, produce explicit CRITICAL diagnostics. Effects can fail or be rejected by an OEM; API control is not proof of audible output. There is no application-level original-stream muter in direct mode.

Primary changed files: `DirectAudioProcessorService.kt`, `DirectAudioEffect.kt`, `DirectEffectRegistry.kt`, `DirectEffectSettings.kt`, `BaseAudioProcessorService.kt`, `RootlessAudioProcessorService.kt`, `MainActivity.kt`, `EngineLauncherActivity.kt`, `PreferenceGroupFragment.kt`, `OnboardingFragment.kt`, notification/status helpers, English/Arabic mode resources, manifest, tests and CI. The existing native DSP source was not changed for this compatibility experiment.

## Executed validation

JDK 17.0.20.1, supported Gradle wrapper, compile SDK 35, NDK 27.0.12077973. Commands:

```
python engineering/build.py -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false :app:testRootlessFdroidReleaseUnitTest :app:assembleRootlessFdroidRelease :app:assembleRootlessFdroidReleaseAndroidTest
python engineering/build.py -Pandroid.injected.build.abi=x86_64 -Pandroid.injected.testOnly=false :app:assembleRootlessFdroidRelease :app:assembleRootlessFdroidReleaseAndroidTest
python tools/verify_installable_apk.py APK --build-tools /workspace/android-sdk/build-tools/35.0.0
python tools/verify_apk_native.py EXTRACTED_ARM64_LIBRARIES /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
```

89 JVM tests passed, zero failures/errors/skips. Added coverage includes effect lifecycle, identity replacement, bounded attachment failures, parameter failure, missing callbacks, concurrent/stale callbacks, release failures, configuration validation and headroom. Existing capture/read/write/muting fault tests remain in the suite. These are mocks where Android APIs require mocks.

ARM64 static delivery gates passed: normally installable testOnly absent/false, signature verification, ZIP integrity, 16 KiB ZIP alignment, correct AArch64 architecture, all packaged native dependency links and required DSP exports. These gates are not a physical installation test.

Initial Android 16 run [39083469713](https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39083469713), source 3fa756f, passed upgrade/fresh startup substeps, five native tests, and one actual capture-to-native-output test. The entire run **failed** its new direct gain assertion: a session Visualizer measured -1698 mB both before and after -12 dB. AOSP inserts Visualizer FIRST and DynamicsProcessing LAST, so that meter was before the effect. The corrected test retains the exact attenuation assertion and observes the output mix after our self-owned session insert. It never records Spotify or modifies its capture restrictions.

The first frozen-binary rerun [39084797402](https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39084797402) passed the actual update and native tests but failed the meter baseline gate: -4953 mB was incorrectly classified as silence by a -4000 mB cutoff. Output-mix measurements include the device media-volume curve. Test-only correction e3dfa66 requires a baseline above -7000 mB (26 dB above Visualizer's -9600 mB floor) while retaining the -12 dB, EQ and restoration assertions unchanged. The production APK was not changed for this rerun.

The next run [39085090101](https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39085090101) passed actual update installation, five native tests and the direct effect measurement. Output RMS was -4953 mB at baseline, -6153 mB for -12 dB gain, -6153 mB for uniform -12 dB EQ, and -4951 mB after release. This is an observed 12.00 dB attenuation and restoration within 0.02 dB on an opted-out synthetic source. The entire run nevertheless failed its held-binder assertion: binding flags 0 did not keep the service alive after stop. The test is being corrected to BIND_AUTO_CREATE so that it actually retains the service instance while checking processing cleanup.

Final frozen-binary run [39098065762](https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39098065762) **passed** on Android 16 x86_64: actual baseline-to-candidate update installation; NativeAudioRecoveryTest (5); DirectSessionEffectTest (1); DirectServiceLifecycleTest (1). Production source is 777e121 and instrumentation source dcf14fd. The lifecycle test uses BIND_AUTO_CREATE, verifies stopped processing while a real service instance remains bound, then unbinds and verifies destruction. It also exercises five repeated start/stop cycles, duplicate-start rejection, backend change and restoration of unsupported controls, with microphone permission revoked. The exact final measurement was baseline -4953 mB, gain -6153 mB, EQ -6153 mB, released -4951 mB. No crash-buffer fatal exception/signal was detected. The earlier failed runs and their test corrections are preserved above; the initial startup/capture stages on source 3fa756f are not represented as being rerun in this focused final job.

The limited EQ/gain compatibility implementation is engineering-validated in the automated environment, with physical-device acceptance pending. This does not complete the original goal of full native ViPER processing for Spotify's opted-out audio; that remains unsupported by this capture path. The ARM64 artifact passed static gates, but the executed emulator APK is x86_64 from the same production source; ARM64 physical execution has not occurred.

Latest owner buffer evidence: 10,752 float samples / 43,008 bytes = 5,376 stereo frames, 112 ms at 48 kHz. Seven steady sparse DSP samples are 6.3–19.7 ms; the startup sample is 244 ms. Every sampled signal peak is zero. This is not a latency benchmark or a guarantee against unsampled stalls. No arbitrary buffer increase or sample-rate change was introduced.

## APK identity

- Test: `RootlessViPER4Android-4.0.7-direct-session-arm64.apk`, versionCode 134, source 777e121.
- Package: `com.alienware377.viper4android.rootless`.
- SHA-256: `43546d23c99f0b16d877d2108f9f2c7decf063c4470f889e2c3f12dbf38b3091`.
- Signing certificate: CN=Android Debug, O=Android, C=US; SHA-256 `dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675`.
- Same repository test signing identity as the earlier owner-tested 4.0.6. This is a signed test artifact, not a private maintainer release key. Install as an update; do not uninstall or clear data.
- Frozen x86_64 test APK SHA-256: `2ef874bb8133c7ae94746eb962a969f9c4970872475f32e8062eec839bd3dd71` is verified against the executed QA fixture; production source 777e121; test-only corrections e3dfa66 and dcf14fd.

## Device acceptance — outstanding

No physical HONOR BRP-NX1 testing of 4.0.7 has occurred. The earlier owner screenshot verifies only 4.0.6 installation/status. An emulator output-mix measurement is not end-to-end audible Bluetooth proof. HONOR cross-app session attachment, soundcore R50i routing, long-run playback and recovery time remain unverified. The complete native ViPER effect set remains unavailable for an opted-out capture source; this experiment is a limited compatibility path.

First owner check: export existing profiles, install the test APK as an update, turn processing on, play Spotify, set Post gain to -12 dB then back to 0 dB. Listen for a clear decrease and recovery; do not increase volume to compensate. Check that status names direct EQ/gain. A flat EQ and 0 dB gain intentionally sound unchanged. If this check fails, stop the test and share the new private diagnostic export.

Only after that succeeds: continuous 60-minute playback; repeated skip/seek and pause/resume; lock/unlock and background operation; disconnect/reconnect R50i and switch speaker/Bluetooth; repeated processing toggles; force-stop only this app through Android's app-info page while Spotify continues. Observe original audio and measure any recovery gap with a stopwatch. Record device/route, observed effect and elapsed recovery time. No real-device rows are marked passed.

## Rollback without deleting settings

Prefer power off, disable Direct session compatibility in Troubleshooting, then power on and give normal system projection consent for the preserved native backend. This avoids a binary downgrade and keeps profiles.

If the new UI cannot open, an optional rollback APK packages the unchanged 4.0.6 audio runtime as versionCode **135**, source [7205005](https://github.com/jimexoon62-star/RootlessViPER4Android/commit/7205005), with the same package/signing identity. Install it as an update without uninstalling. It restores the prior runtime and its capture limitations; it is not an additional DSP fix. SHA-256: `1b0e2418e7c860f7013c0a8b8ed293f98ae7043fc260d781844944361a044c17`. Static APK gates passed; physical rollback installation remains untested. After installing 135, Android can reject the lower 134 test build; any subsequent test build must use a higher version code. Do not clear data to force a downgrade.
