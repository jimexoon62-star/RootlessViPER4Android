# 4.0.4 — stop playback-capture inventory from restarting its own pipeline

The owner-supplied October 17 application trace establishes a regression in 4.0.3: at 01:44:07.529 capture resources are created; creation repeats at 08.060, 08.380, 08.729 and 09.115; at 09.184 the service records `Audio fail-open: route changes exceeded 4/30s`. The same pattern recurs across later activations. This is direct evidence of the service's route-recovery budget stopping processing, not evidence of a Spotify/AAudio incompatibility. The trace contains no Java crash stack proving activity/process death.

The implementation introduced an unfiltered AudioDeviceCallback: any addition/removal called routeChanged and rebuilt capture. Playback-capture AudioRecord registration itself changes remote-submix device inventory. Such events must not be treated as physical output selection changes. The earlier unit test only supplied an empty device-removal list and therefore failed to represent real routing semantics.

Correction:

- Initial and later device additions never request a restart; inventory does not establish selection.
- Device removals request recovery only for the selected physical output ID, only for sink devices, and never for TYPE_REMOTE_SUBMIX.
- Actual output selection changes continue to be observed through the active AudioTrack routing listener, ignoring null/remote-submix/non-output routing.
- First route assignment is not a change. Stale tracks are ignored. Explicit stop clears the remembered output before another generation can start.
- The recovery budget remains for genuine output route changes; callback filtering fixes the cause rather than increasing the retry threshold.
- New regression tests deliver 100 submix addition/removal cycles and confirm no effects are released, no failure is raised and no route budget is consumed. Initial inventory, unrelated output removal and input removal are also ignored. Removal of the actual Bluetooth output still releases muting before the rebuild.

Version 4.0.4-routefix-test / code 131 uses the same package and signing certificate, preserving installed data through an update. Build with `-Pandroid.injected.testOnly=false`; validate the actual APK with tools/verify_installable_apk.py before delivery. No owner diagnostic dump or third-party application details are published.

## Executed validation

- 56 JVM tests passed, zero failures/errors/skips. The new callback regression tests pass alongside existing fail-open/I/O coverage.
- Signed ARM64 APK built from b590c4db2f019bb0102bb4a0304c044944aff927. Filename: RootlessViPER4Android-4.0.4-routefix-arm64.apk; version 4.0.4-routefix-test / 131; 33,616,426 bytes.
- SHA-256: ceb44c7fefcc0d87c662bd1a212432e23d09dd16300d5ea5dbe59fa490380148.
- Actual downloadable APK: testOnly absent; signature, ZIP integrity, 16 KiB alignment, ARM64 native dependencies and embedded source revision verified. Browser download independently rehashed to the same digest.
- Android 16 x86_64 emulator: installation without `-t`, official v4.0.0-to-candidate update, fresh startup, startup with and without record/dump permission setup, and post-revocation startup completed with a live foreground application and no fatal Java/native exception in the captured buffers.
- Five Android JNI/instrumentation tests passed (`OK (5 tests)`): persistent diagnostic export, repeated allocation/free with stereo float passthrough, PCM16/silence boundaries, impulse/gain changes, and invalid array bounds.
- Corrected-runtime emulator run: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/38992726758 (success). CI merge revision 96d78f38a748949b9e85d710690bfe7a3617083f carries the same application runtime source plus the workflow. The delivered APK is ARM64; the executed emulator was x86_64. These are explicitly different ABI artifacts.
- The first CI attempt failed before building because setup-android requested the removed `tools` SDK package. Its configuration was corrected to request platform-tools. That failed setup is not counted as passing tests.

The emulator tests did not activate MediaProjection or play Spotify through the processing service, and do not demonstrate Bluetooth audibility or recovery on HONOR. The callback regression is covered using production service control flow with mocked AudioDeviceInfo/Handler adapters. Physical processing and audible acceptance remain pending. The full owner dump stays private.

Download: https://github.com/jimexoon62-star/RootlessViPER4Android/raw/refs/heads/delivery/rv4a-4.0.4-routefix/test-builds/RootlessViPER4Android-4.0.4-routefix-arm64.apk
