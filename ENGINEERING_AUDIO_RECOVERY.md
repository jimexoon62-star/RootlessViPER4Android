# RootlessViPER4Android audio recovery — engineering evidence

Status: diagnostic repair candidate. Target-device installation and audible playback have not been demonstrated. Do not describe this APK as operationally verified.

## Scope and source

Upstream: alienware377/RootlessViPER4Android, viper-extras, base 302b549791bdbce296e2f8a1a8639f0fcd56e084.
Authorized fork: jimexoon62-star/RootlessViPER4Android, repair/audio-recovery.
Candidate source commit: 680049242a2d44a5a93e4068e1926971ccaa73cd.
Build variant: rootlessFdroidRelease. Package: com.alienware377.viper4android.rootless. Version: 4.0.1-recovery-test (128). ABI: arm64-v8a. minSdk: 29; targetSdk: 34; compileSdk: 35. Android 16 compatibility still requires target-device testing.

## Findings supported by direct inspection

1. A clean cloud build at 9e261de82da2a82d7c6f790c3d971d9d17b9fad6 compiled successfully and passed unit tests, but its APK's libjamesdsp.so exported AELI and omitted JamesDSPGlobalMemoryAllocation. The wrapper imported that missing function. The CMake HAL target has OUTPUT_NAME jamesdsp and hidden visibility. Gradle's default external-native target selection packaged this HAL output in place of the JNI DSP core. EXCLUDE_FROM_ALL alone did not prevent the packaging problem. Explicit rootless native target selection addresses this observed defect. This reproduces a mechanism matching Failure A; the original crashing development APK was not available for forensic identification.
2. The earlier delivered APK, SHA-256 5edfbbceb4ac61aab0f079236d1315c8c63c802674a7dd9bff03264727951ef4, contains a core exporting the mandatory allocation function and a wrapper depending on that core. Its native linkage does not explain the owner's separate silence-on-activation observation.
3. The application deliberately mutes source audio sessions through DynamicsProcessing input gain -200 dB or a hidden volume effect. Ordinary playback recovery requires releasing these effects, not merely stopping MediaProjection. The existing enable listener attempts to re-enable disabled effects; teardown now detaches both listeners before disabling and releasing, with independent cleanup attempts.
4. The existing processing loop ignored read/write counts. Missing checks are confirmed source defects; they have not been established as the target-device cause of silence.

## Corrective changes

- Restrict rootless APK native targets to DSP core, JNI wrapper, import toolbox, and logging connector.
- Enforce --no-undefined, 16 KiB ELF page alignment, and non-executable stack on the APK libraries.
- Validate JNI PCM input/output bounds, stereo alignment, and JNI array acquisition. Normalize native allocation failure to a zero handle and reject it in Kotlin.
- Process only samples actually read; reject negative or invalid counts; complete partial writes with bounded non-blocking retries.
- Measure PCM RMS/peak before and after DSP; reject non-finite output; log playback head, underruns, routing, format, rate, and device firmware.
- Stop the service on pipeline failure, stalled I/O, persistent silent DSP output with meaningful input, or prolonged silent capture while source sessions remain muted.
- Detach mute listeners and release source effects during service teardown; stop projection and audio resources. Refuse overlapping worker restart; close the native engine once after its worker terminates.
- Stop on projection revocation even when the activity is visible, and avoid redelivery of a stale projection grant.
- Size capture/output buffers against platform minimums and align byte counts to stereo frames.

## Deliberate limitations

The 8-second silent-capture guard is a conservative diagnostic policy. Paused media retaining a muted session may trigger it; the guard cannot distinguish paused content from unsupported capture or an OEM capture problem. It stops processing rather than claiming a reliable PCM bypass. Stopping resources and releasing effects are implemented recovery actions; successful ordinary-audio recovery on the affected phone remains unproven.

Existing restricted-app exclusion and UID include/exclude mechanisms are retained. No processing guarantee is made for Spotify, YouTube, protected media, disallowed capture policies, or any OEM route. No root, bootloader, system audio-policy modification, or personal-data deletion was performed by this task.

## Device test pending

The manufacturer, exact model, firmware/build, and second phone's platform have not been provided. No ADB session or direct connection to the target phone exists. No target-device installation, MediaProjection grant, speaker/Bluetooth/wired playback, effects audibility, sustained playback, repeated activation, capture-loss recovery, or restoration after errors has been measured.

The app already exports its application log through Settings → Troubleshooting → Export audio session dump. The candidate adds stage measurements and firmware metadata there, allowing phone-only diagnosis without a PC, root, or exposed debugging port.

## Controlled owner test sequence

1. Establish normal playback of an unprotected local file with processing disabled. Keep output route and media volume unchanged.
2. After identifying device details, install the candidate and verify startup. Use one source and one route first.
3. Authorize MediaProjection, enable processing, record whether audio remains audible, and export the audio session dump without restarting the app.
4. If silence occurs, disable processing. If ordinary audio does not return, Android Settings → Apps → RootlessViPER4Android → Force stop. Restart the phone only if necessary; do not clear data or reset it.
5. Use the log to choose one subsequent change: DSP disabled, PCM format, capture eligibility, or output route. Do not change all settings at once.
6. Only after baseline success test effects, 10 minutes of playback, ten start/stop cycles, route changes, and supported alternate sources. Match installed APK SHA-256 to the delivered APK.

Signing uses the repository's Android debug certificate. It is not a new production signing identity. Its fingerprint matches the earlier delivered APK's v2 certificate; installed variants with other package IDs or certificates require separate inspection. No uninstall is requested in this report.

## Delivered APK integrity

- Filename: RootlessViPER4Android-4.0.1-recovery-test-arm64.apk
- Size: 33,602,990 bytes
- SHA-256: `b47559b81ffa312f9a7db9477a2ce9e0ca81c488596a3568507cce7f3a9d96a5`
- Embedded build timestamp: `2026-10-13T17:39Z` (extracted from delivered APK DEX).
- Cloud inspection timestamp: `2026-10-13T17:42:34Z`.
- Source commit: `680049242a2d44a5a93e4068e1926971ccaa73cd`.
- Signature: apksigner verify succeeded; APK Signature Scheme v2; one RSA 2048-bit signer.
- Certificate SHA-256: `dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675`.
- Extracted native libraries: libjamesdsp.so, libjamesdsp-wrapper.so, libjdspimprestoolbox.so, libcrashlytics-connector.so.
- ARM64 ELF verified. Mandatory DSP allocation export present. AELI absent. DSP wrapper DT_NEEDED and library SONAME/dependency chain verified.
- All non-weak mandatory imports resolve against these packaged libraries plus Android API 29 platform stubs. Platform-dependent loading on the affected Android 16 phone remains pending.
- ELF LOAD segments and stored APK native-entry alignment checked for 16 KiB pages.
- Downloaded artifact SHA-256 matches GitHub's artifact digest; copied delivery APK SHA-256 matches the independently inspected cloud APK.

## Verification results

| Check | Result | Scope |
|---|---|---|
| ARM64 native build with strict linking | Pass | Linux build with Android NDK 26.1 |
| Rootless release APK build | Pass | GitHub Actions, exact source commit above |
| Pure I/O regression tests | 11 passed, zero failures/errors | Both local Kotlin runner and release Gradle unit tests |
| Android native regression tests | 4 passed, zero failures/errors | Android 16 / API 36 x86_64 emulator; debug variant from the same source commit |
| JNI startup and repeated allocation/free | Pass | Ten native engine cycles in emulator test |
| Float stereo passthrough | Pass | Non-silent different left/right signals, numerical comparison |
| PCM 16-bit and output boundaries | Pass | Reference samples and unchanged sentinel tail |
| Silence and impulse | Pass | Numerical assertions |
| DSP gain change | Pass | +6 dB output asserted against expected amplitudes |
| Invalid JNI array bounds | Pass | Expected exception instead of native out-of-bounds processing |
| Partial read/write, negative counts, alignment and non-finite samples | Pass | Pure I/O tests |
| APK signature and native packaging | Pass | The actual delivered ARM64 release APK |
| Installation of delivered ARM64 APK on target phone | Not tested | Requires owner/device access |
| Audible playback, source compatibility, routes and recovery | Not tested | Requires owner/device access |
| Service lifecycle, MediaProjection revocation, track recreation and repeated service start/stop | Not automated or device-tested | Recovery code implemented; functional validation outstanding |

The emulator instrumentation run installed and exercised the x86_64 debug test application. It did not install or play sound through the delivered ARM64 release APK. Its four passing tests are not target-device installation or playback acceptance evidence.

The first emulator attempt failed while downloading its system image before any tests ran. The final run completed all four tests successfully. Earlier native packaging verification correctly rejected a build containing the HAL library; that rejected APK was not delivered.

Build and test run: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/38440147976
Source: https://github.com/jimexoon62-star/RootlessViPER4Android/tree/repair/audio-recovery
Native packaging failure evidence: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/38439031534

## Outstanding completion blocker

The independent build, package, signature, and executable JNI/DSP tests are complete. The target phone is not connected to this workspace and its exact firmware is unknown. Failure B's stage and root cause remain unconfirmed. The smallest next owner action is to provide the phone's About phone screen (manufacturer/model, OS and build number); then proceed with the controlled local-file playback test and export the app's diagnostic dump. No computer or second-phone debugging tool is needed for this first diagnostic path.
