# 4.0.6 signal status and known-source capture validation

This revision does not change Spotify, opt-out flags, Android capture security, or original-stream suppression rules. It adds visible API-evidence status and a separate engineering fixture to test the previously missing capture boundary.

## Status

The main screen toolbar and foreground notification distinguish waiting for a usable signal, native capture restriction with no owned sessions, observed nonzero capture/replacement signal plus owned sessions and advancing output head, and stopped processing with original playback requested. The state explicitly describes signal/API progress, not independently verified audibility. A running service or an AudioTrack alone is insufficient. Status updates are on transitions, using the existing 500 ms watchdog, and local broadcasts stay inside the app. Arabic and English strings are included. Rootful behavior remains unchanged.

## Separate synthetic fixture

`audio-capture-probe` is a separate application, never included in the shipped ViPER APK. It has no advertisements, telemetry, network permission, muting effect, or connection to Spotify. With normal projection consent it captures only its own UID and generates bounded stereo 997/1499 Hz signals at 48 kHz. It compares three new track instances: ALLOW_CAPTURE_BY_ALL, ALLOW_CAPTURE_BY_SYSTEM, then ALL again. Each stage discards 1.5 seconds of settling data and measures 3.5 seconds. It records sample counts, RMS, peak and source playback head; a small synthetic PCM block is retained for the JNI test.

Android 16 CI accepts the real system consent dialog in the disposable emulator; it does not grant privileged projection access or alter capture app-ops. Required assertions: nonzero capture in both allowed stages, near-zero capture despite returned frames in the system-only stage, and advancing source heads. The actual captured block is passed through the application's real JNI engine at +6 dB; every output sample must match expected stereo gain within tolerance. A replacement AudioTrack must then write and advance its head. This is API-level integration evidence, not physical or independent audible measurement, and is not the full Spotify/service/mute path. Missing or failed fixture data fails the test.

The standalone probe is not delivered for owner installation. If it passes, owner testing can focus on whether Spotify's actual track policy and processing behavior match the diagnosed restriction.

## Execution

Build/test commands and outcomes will be recorded after execution. Physical HONOR/Spotify/Bluetooth acceptance remains outstanding. This revision must not be described as restoring Spotify processing until that is demonstrated.

Local frozen-runtime build (6aada4c): `python engineering/build.py -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false :app:testRootlessFdroidReleaseUnitTest :app:assembleRootlessFdroidRelease :audio-capture-probe:assembleDebug :app:assembleRootlessFdroidReleaseAndroidTest` succeeded in 58 seconds. 74 JVM tests passed with zero failures/errors/skips. The actual ARM64 APK passed signature, absence of testOnly, ZIP, 16 KiB APK alignment and native ABI/dependency/export checks. Embedded runtime source was verified as 6aada4c. SHA-256: `16db64d81869c2faa56d7b8d4ab3b49807a1b276499419364894a61d7ea1e94c`.

Executed failures retained for clarity: the first fixture build used Files.writeString, unavailable in Android SDK stubs, and was corrected to Files.write. CI attempts 39070532421 and 39070612017 passed startup/update and the five native tests, then failed to locate the standalone probe APK; capture was not executed in either attempt. Harness commit 92c5bd2 finds the actual intermediates/APK path. `git diff --exit-code 6aada4c 92c5bd2 -- app/src buildSrc audio-capture-probe settings.gradle` confirms identical application/probe runtime sources; only CI/harness files changed. Run 39071145835 then executed the probe but failed because intentional writer interruption was classified as an error. Source correction 9bbcd6d distinguishes intentional cleanup. Attempt 39071844878 accidentally reused a stale pre-correction fixture APK, verified by its old DEX error string; it is not a passing capture test. The corrected artifact was selected from the actual outputs directory and its DEX was checked for the new classifier and absence of the old message before rerun.

## Executed Android capture result

Final fixture/native run https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39072145337 completed successfully. It reused the unchanged x86_64 application and instrumentation APKs from run 39071145835, with the separate fixture rebuilt from 9bbcd6d. QA revision 1dcf82a includes the verified fixture binary/checksum and shorter capture-only harness. No application runtime source changed from 6aada4c.

| Stage | Returned stereo samples | RMS | Peak | Source head (frames) |
|---|---:|---:|---:|---:|
| Capture allowed | 335872 | 0.0640319393 | 0.100006104 | 237184 |
| System-only capture | 335872 | 0 | 0 | 239360 |
| Capture allowed again | 335872 | 0.0640314638 | 0.100006104 | 240448 |

NativeAudioRecoveryTest: OK (5 tests), instrumentation code -1. CapturedSignalTest: OK (1 test), code -1. The latter verifies separate left/right reference frequencies, native +6 dB gain on every captured sample, complete nonblocking writes, and advancing replacement playback head. These are six executed Android instrumentation tests, separate from the 74 JVM tests.

Startup/original-to-candidate upgrade/fresh-install checks passed in earlier runs before the fixture-stage failures; those full runs are correctly marked failed. The final capture-only run did not repeat the full startup sequence. The delivered APK is ARM64; the executed emulator APK is x86_64. No physical-device or independently audible output claim is made. This is not a complete test of the actual RootlessAudioProcessorService/Spotify/muting path.

The status is an aggregate indication of session policy and captured/output signal progress, not proof that a particular application's sound is being processed. The required owner gate is still a short actual Spotify/Bluetooth playback and gain comparison, then the longer acceptance suite if it passes. No physical device is attached to this workspace.
