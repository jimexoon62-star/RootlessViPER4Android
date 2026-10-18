# 4.0.8 session recovery — engineering change record

## Change and evidence

Runtime source: [7c6e128](https://github.com/jimexoon62-star/RootlessViPER4Android/commit/7c6e128). Test-only follow-up: 4748038. Review branch: `fix/rootless-audio-blackout-honor-android16` in the authorized fork. Upstream and unrelated repositories were not changed.

The previous BaseSessionManager returned immediately for a nonblocking playback/session notification while its dump mutex was locked. A source appearing during that read could therefore be absent from the completed snapshot, with no scheduled follow-up until another notification. This is a confirmed source-level loss path, not proof that it initiated the owner's original October 16 blackout.

SessionPollCoordinator now keeps one in-flight read and one conflated pending update. New requests invalidate older results; periodic reads share the same mutex. Delivery remains on the main dispatcher, dump I/O remains off-main. Closing the coordinator rejects late results and callbacks; no worker is restarted after shutdown. Read errors are bounded to three diagnostic reports until successful delivery and do not kill the event consumer. There is no new fixed polling timer, buffer increase or DSP/sample-rate change.

Production changes: BaseSessionManager.kt, new SessionPollCoordinator.kt, and version metadata (136). Existing full-backend fail-open/mute ownership, checked audio I/O, signal gating, bounded recovery, native worker retirement, direct-effect ownership and release protections are retained.

## Source restriction is a separate issue

The latest private owner dump installs 4.0.7 / 777e121. Spotify's native track has NO_MEDIA_PROJECTION; public AAudio zero flags do not override it. The full capture-based native ViPER engine cannot process those unavailable samples. Other listed Symfonik/Facebook tracks lack that particular flag; this is track-specific evidence, not a permanent app-wide policy.

Spotify's idle SoundPool session zero and its active AAudio nonzero session coexist in the processed dump. Skipping zero was correct and does not establish a session-ID parser defect. The earlier assistant suggestion of that defect was withdrawn.

Direct mode uses Android DynamicsProcessing on original sessions: graphic EQ and gain with automatic positive-EQ headroom and a fixed stereo-linked limiter. It does not capture, mute originals, create replacement tracks or bypass capture policy. It is not the complete native ViPER engine. Unsupported settings remain saved and the native backend remains selectable. OEM effect attachment/control and actual audible output still require device validation.

## Executed checks

JDK 17.0.20.1, supported Gradle wrapper, SDK 35, NDK 27.0.12077973.

```
python engineering/build.py -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false :app:testRootlessFdroidReleaseUnitTest :app:assembleRootlessFdroidRelease
python engineering/build.py -Pandroid.injected.build.abi=x86_64 -Pandroid.injected.testOnly=false :app:assembleRootlessFdroidRelease :app:assembleRootlessFdroidReleaseAndroidTest
python tools/verify_installable_apk.py APK --build-tools /workspace/android-sdk/build-tools/35.0.0
python tools/verify_apk_native.py EXTRACTED_ARM64_LIBRARIES /workspace/android-sdk/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf
```

94 JVM tests passed, zero failures/errors/skips: existing 89 tests plus five fault-injection tests for arrival during I/O, burst coalescing/provider changes, closing during a read, concurrent periodic/event reads, and bounded failures with later recovery.

ARM64 signature, testOnly absent/false, ZIP integrity, 16 KiB ZIP alignment, native architecture/dependencies and required DSP exports passed. The four required ARM64 libraries are retained. Frozen executed x86_64 APK SHA-256: `76e5c2fb71184d2f1c4318901f5ca084fcd62333bcec4e30316b558784b57c1f` (same runtime source; different ABI, not physical ARM64 execution). These checks do not constitute physical installation or audible playback.

Earlier focused run 39105791041 passed two independent opted-out AudioTrack source apps with actual service gain -12.01 dB and restoration within 0.01 dB. Run 39106381872 on unchanged 4.0.7 passed shared AAudio (requested 44.1 kHz) alongside AudioTrack (48 kHz), through both discovery providers, and the official mpv player. AAudio startup logs explicitly show a shared legacy output, not MMAP. Measured source-pair RMS was -4650 -> -5852 -> -4650 mB; mpv was -4951 -> -6153 -> -4951 mB. The entire run nevertheless failed VLC's baseline gate (-9600 mB) before ViPER was started: VLC 3.6.3 requires its normal first-run storage approval before opening even the granted synthetic content URI. The corrected fixture uses the real Android Settings approval UI in the disposable emulator, not an app-op override, and waits for bounded source startup without lowering the signal threshold.

An initial setup run 39105604352 failed installing a test-only fixture without `-t`; 39105748530 was canceled after identifying an incorrectly staged duplicate-package artifact. These were not audio processing passes. Package IDs, ABI contents and hashes were checked before subsequent runs.

Final new-runtime validation: [39107425868](https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39107425868) **PASSED**, runtime 7c6e128, test-only source 4748038, QA eba00fd. Actual signed upgrade from 134 to 136 succeeded. NativeAudioRecoveryTest: 5 passed; DirectSessionEffectTest: 1 passed; DirectCrossAppTest: 4 passed; DirectServiceLifecycleTest: 1 passed (microphone permission revoked). The latter verifies repeated start/stop, duplicate-start rejection, retained BIND_AUTO_CREATE cleanup, backend switch and restoration of native controls. The crash-buffer fatal check passed.

| New-runtime case | Baseline RMS (mB) | Reduced (mB) | Restored (mB) | Observed attenuation |
| --- | ---: | ---: | ---: | ---: |
| Official VLC 3.6.3 | -4951 | -6153 | -4951 | 12.02 dB |
| AudioTrack + shared AAudio, AudioPolicy discovery | -4652 | -5851 | -4650 | 11.99 dB |
| AudioTrack + shared AAudio, AudioService discovery | -4652 | -5852 | -4650 | 12.00 dB |
| Official mpv 2026-09-17 | -4951 | -6153 | -4951 | 12.02 dB |

The AAudio source started while the real service already processed the first source, with no manual poll/toggle/session-ID injection. Adding it increased measured power from -4951 to -4652 mB, proving that it contributed signal before attenuation was checked. The direct self-owned test additionally measured gain and uniform EQ: -4953 -> -6153 -> -4951 mB after release. Maximum restoration difference in these readings was 0.02 dB. These are emulator output-mix measurements, not physical audible acceptance. There are no unresolved failures in this final automated run. No inference is made that it proves end-to-end Spotify playback on HONOR.

## APK identity and safe installation

- File: RootlessViPER4Android-4.0.8-session-recovery-arm64.apk; versionCode 136.
- Package: com.alienware377.viper4android.rootless.
- SHA-256: 8f42093da439792078e7e64927833948d0422f271185685562208224bc1f0a66.
- Certificate: CN=Android Debug, O=Android, C=US; SHA-256 dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675.
- Same test signing identity as earlier owner-installed builds. This is a signed test APK, not a private maintainer release signature. Install as an update; export profiles first; do not uninstall or clear data. No profile schema or saved parameter migration was added.

## Device acceptance and remaining limits

The owner reports hearing effects in other applications on the installed build; names, backend, settings and route for that observation are not specified. This is useful owner-reported evidence, not a measured Spotify result and not a test of 4.0.8.

Physical HONOR BRP-NX1 / Android 16 / Spotify / soundcore R50i execution of 4.0.8 remains outstanding. No 60-minute run, route reconnection, screen-lock/background acceptance or deliberate-failure recovery time is marked passed. Emulator meters and API control cannot prove physical audible Bluetooth playback. The exact original blackout initiator remains unknown. Full native ViPER processing of Spotify's opted-out samples remains unavailable through this capture path.

A minimal first physical check is Spotify playback under Direct session compatibility, comparing Post gain -12 dB and 0 dB without increasing device volume. Only after a clear effect is heard should the existing 60-minute/route/lock/skip/stop acceptance matrix be executed. During a force-stop of this app alone, observe original Spotify audio and record any gap with a stopwatch. Never clear app data.

## Rollback

Settings/profiles are preserved. Changing the backend remains available through Troubleshooting; the full native path still requires legitimate projection authorization and still respects source restrictions.

A separate versionCode 137 rollback packages the previous 4.0.7 runtime (777e121 plus version-only commit ed7e1b2), with the same package/certificate, so Android can accept it as an update after 136. It is a rollback, not a DSP fix. Its ARM64 build, signature/ZIP/alignment gates and native dependency/export checks passed. SHA-256: 4a4ca6f237d1ef43836b7392d50153ff557aa02b6fcf50056dabd24efd257193. Download only if needed: [previous-runtime rollback APK](https://github.com/jimexoon62-star/RootlessViPER4Android/raw/refs/heads/delivery/rv4a-4.0.8-session-recovery/test-builds/RootlessViPER4Android-4.0.7-rollback-arm64.apk). Physical rollback execution is pending. After 137, a future test must use a higher versionCode; do not uninstall to force a downgrade.

Automated engineering validation of this session-recovery correction is complete; HONOR/Spotify physical acceptance and full native processing of opted-out audio are not claimed.

Delivery verification: both published APK URLs were re-downloaded; their SHA-256 hashes matched the frozen artifacts. The re-downloaded primary APK also passed signature, testOnly, ZIP integrity and alignment verification. Local ADB reports zero connected devices.
