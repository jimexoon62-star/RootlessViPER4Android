# Full native ViPER engine through authorized shell routing

Status: implementation and automated validation in progress. Device acceptance
has not been performed. No conclusion that Spotify works on HONOR follows from
compilation, API progress, or the standalone capture feasibility probe.

## Problem and evidence

The owner's Spotify source has native NO_MEDIA_PROJECTION, with no observed
NO_SYSTEM_CAPTURE flag. The ordinary playback-capture path returned zeros while
other applications were capturable. Public AAudio attributes alone did not
expose these native flags; AAudio did not establish an MMAP/exclusive path.
The exact initiating event of the original October 16 blackout remains
unproven. Earlier unvalidated session-ID mapping explanations were withdrawn.

The direct compatibility backend only offers Android DynamicsProcessing EQ and
gain. It cannot implement the complete native ViPER effects. This change adds a
separate full-engine backend rather than relabelling that limited backend.

## New processing path

User-authorized Shizuku in **ADB UID 2000 mode** starts a small app_process worker.
The worker uses its actual shell identity and actual com.android.shell package
attribution. It checks MODIFY_AUDIO_ROUTING and registers an AudioPolicy matching
selected external application UIDs AND media/game/unknown playback usages. The
application excludes its own UID, privileged/system UIDs, blocked apps and source
owners with known NO_SYSTEM_CAPTURE. Unknown flags alone cannot authorize routing.
No source capture policy, Android app-ops or security setting is changed.

A privileged copy-only mono 16 kHz monitor detects signal while original audio
continues directly. These low-fidelity samples **never enter the DSP**. When
signal appears, the worker switches to redirected stereo PCM16 at the physical
44.1/48 kHz output rate. The original app's native JamesDspLocalEngine receives
that PCM and applies the original settings/profile chain, including native
ViPER effects and convolver. Its output is played by the app's AudioTrack, whose
UID is never selected for capture. The old muting-effect factory is not used.

Quiet input/output returns to copy monitoring without classifying ordinary
silence as a DSP failure. Sustained silent input also has a bounded 20-second
lease, preserving the existing engine's allowance for long effect tails. A
bounded number of inconsistent positive-monitor/zero-full-capture probes falls
back to direct playback. Repeated engine/capture/output errors have two retries
with backoff; retrying never holds the original route suppressed.

## Fail-open ownership

Each generation owns exactly one input, output and native engine. Only complete,
validated AudioTrack writes renew the shell worker's renderer lease. Stdin EOF
or 1.5 seconds without acknowledgements unregisters routing before capture
release, then terminates the worker. Process death independently invokes the
platform's AudioPolicy Binder-death cleanup. The app also checks input/output
progress, output state/head progress and measured underrun deltas. It stops
renewing routing on errors; counters are not interpreted as physical audibility.

Shutdown atomically retires a generation, closes the acknowledgement channel,
flushes/releases old output and prevents stale code from renewing the lease.
A release acknowledgement is awaited for at most 250 ms. Missing/failed release
is explicitly logged and the policy-owner process is terminated. There are no
original-stream muting effects to re-enable from stale listeners.

Native settings, processing and final disposal execute serially on their owning
worker. Unlike the legacy asynchronous preference path, the new backend waits
for settings application, treats rejected configuration as failure, and frees
the engine only after processing returns. A stuck native worker prevents creation
of duplicate replacement pipelines while the original stream remains direct.

## Validation limits

The standalone Android 16 feasibility probe passed: copy capture returned zero;
full-quality shell routing produced 239616 stereo frames at 48000 Hz with
237558 nonzero left frames and RMS 0.1408156. Both policies were unregistered.
This probe used a synthetic opted-out source, not Spotify or the owner's phone.

Final integration test results, executed APK hashes and signing metadata will be
added after execution. No physical Bluetooth, screen-lock or 60-minute acceptance
has occurred in this environment; no phone is connected through ADB.

## Confirmed integration run (2026-10-18)

Source `0878562`, Android 16 API36 x86_64 emulator, real official Shizuku
permission and app_process transport: 27/27 instrumentation tests passed
(5 native recovery, 14 native effect measurements, 7 routed engine scenarios,
1 legacy lifecycle). Evidence:
https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39116660733

Injected failures restored the synthetic source meter baseline in 1286–3637 ms
observation windows, including meter acquisition time. These are independent
PCM meter observations, not physical audible-output measurements or precise
latency guarantees. The AAudio source requested 44100 Hz and reopened correctly.

### Critical AAudio limitation

The previous run 39115616856 failed its AAudio scenario: the synthetic client
did not reopen after AAUDIO_ERROR_DISCONNECTED. Releasing routing alone did not
restart that client's disconnected stream. The corrected fixture implements
normal bounded client-side reopen, with an 800 ms deliberate delay. Production
routing now allows a bounded two-second reopen grace and limits session-driven
rearming. Correcting the fixture is NOT a Spotify fix. Whether Spotify on HONOR
handles this transition remains unverified; a nonrecovering client can remain
silent even after policy release. This prevents declaring the application
fully fail-safe or the owner assignment complete at this stage.

### Pending changes

The subsequent digital-zero DSP guard and partial-buffer integrity tests require
new execution. They are not covered by the successful run above.

## 2026-10-20 build and local verification

Source e985548: 115 JVM tests passed, zero failures/errors/skips. Executed:

```
python engineering/build.py -Pandroid.injected.testOnly=false :app:assembleRootlessFdroidRelease :app:assembleRootlessFdroidReleaseAndroidTest :app:testRootlessFdroidReleaseUnitTest -Pandroid.injected.build.abi=x86_64
python engineering/build.py -Pandroid.injected.testOnly=false :app:assembleRootlessFdroidRelease -Pandroid.injected.build.abi=arm64-v8a
```

Both builds succeeded. ARM64 APK passed verify_installable_apk.py (normal package
installer flag, signature, ZIP integrity, 16 KiB ZIP alignment) and
verify_apk_native.py (AArch64 architecture, packaged dependency closure, native
exports and wrapper linkage). This is static verification, not an ARM64 install.

Package com.alienware377.viper4android.rootless, versionCode138,
versionName4.1.0-full-engine-test. Test signer CN=Android Debug,O=Android,C=US,
certificate SHA256 dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675.
It can update earlier builds with this same signer; upstream signer compatibility
is not established. Do not uninstall or clear app data on signature mismatch.
No additional uses-permission declarations were introduced for the full backend.
Shizuku ADB authorization is required and increases the trusted execution scope.

APK SHA256: 36a4553464f42ffd618c49ed6f2413db6bf40118d43c5693671f5369abc08e86.
Rollback APK SHA256: d6de7c6e01c5b32e25b7fb2f56a91f86814f8e04e99863028461815b0ebb14bf.
Rollback versionCode139 is old 4.0.7 runtime with only version metadata changed,
source b327b47. Same signer permits updating over138 without data clearing;
this rollback has not been installed on the owner's device.

Latest 28-scenario instrumentation execution:
https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39509693743
Status must be read from executed evidence before marking it passed. The
previous27-test pass does not validate the new digital-zero scenario.
