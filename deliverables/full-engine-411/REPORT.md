# Rootless ViPER 4.1.1 — bounded policy-owner lifetime

Status: automated engineering checks passed. HONOR/Spotify physical acceptance remains unexecuted. The overall owner acceptance is NOT complete.
The previous AAudio nonrecovering-client limitation remains unresolved; this
change does not claim that restarting an external player's disconnected stream
is within ViPER's control.

## Actual implementation change

4.1.0 started its renderer lease after policy registration, AudioRecord creation
and recording start. Its timeout then called the same potentially blocking
cleanup before exiting. A stuck initialization or teardown could therefore
prevent the timeout from restoring original routing.

ShellRouteGuard now starts before policy registration. It grants initialization
at most4000ms, then requires renderer acknowledgements at least every1500ms.
Closing retires the worker immediately; a dedicated cleanup thread attempts
unregistration and recorder release. An independent guard exits the policy-owner
process no later than250ms after closing if cleanup is still blocked, delegating
final policy removal to Android's Binder-death cleanup. These are software
deadlines plus OS scheduling overhead, not guaranteed acoustic recovery times.
Stale acknowledgements cannot reset deadlines or resurrect a retired worker.
An unconfirmed release is explicitly logged before process termination.

The original full native JamesDSP/ViPER processing, profiles, buffers and sample
rates remain unchanged. No source capture policy or Android security restriction
is bypassed. Full stereo capture still requires genuine Shizuku ADB authorization.

## Tests

Seven deterministic guard tests cover initialization timeout, blocked cleanup,
normal/idempotent cleanup, steady renderer lease, stale callbacks, and premature
acknowledgements during initialization, plus simultaneous shutdown and callbacks. Two integration tests run a separate
instrumentation-only worker under real Shizuku, intentionally block it after
real policy registration or before real unregistration, and verify process exit
and synthetic source baseline restoration. Executed results and evidence are recorded below.

The full existing native effects and recovery suites are also required because
the worker's startup and shutdown order changed. No mocked pass proves audible
HONOR playback or Spotify AAudio recovery.

## Package and rollback

Version4.1.1-full-engine-test, versionCode140; same package and test certificate
as4.1.0. It updates138/139 if Android accepts the matching signature; do not
uninstall or clear data if signatures differ. A rollback package must have
versionCode141 or higher to update this build without a downgrade/data wipe.
The older rollback139 is insufficient for140.

## Still required

Actual HONOR BRP-NX1 / Android16 / Spotify / soundcore R50i playback, audible
native effect comparison, continuous60minutes, seeking/skipping, pause/resume,
lock/background, realBluetooth disconnect/reconnect and speaker switching,
repeated enable/disable, intentional service failure and measured recovery.
A client that ignores AAUDIO_ERROR_DISCONNECTED can remain silent after policy
release; this must not be hidden behind the passing tests of a well-behaved
synthetic AAudio client.

Local source c0b9a2c:122 JVM tests executed, zero failures/errors/skips.
The final Android30-scenario result is recorded below.

## Executed build and static gates

Executed via the local wrapper that supplies the session proxy, JDK17 and Android
SDK paths, using the project's Gradle wrapper8.10.2:

```
:app:testRootlessFdroidReleaseUnitTest :app:assembleRootlessFdroidRelease :app:assembleRootlessFdroidReleaseAndroidTest -Pandroid.injected.build.abi=x86_64 -Pandroid.injected.testOnly=false
:app:assembleRootlessFdroidRelease -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
```

122 JVM tests passed with zero failures/errors/skips. ARM64 build succeeded.
verify_installable_apk.py passed normal-installer flag, APK signing, ZIP integrity
and16KiB ZIP alignment. verify_apk_native.py passed AArch64 dependency closure,
DSP exports and wrapper linkage. All four native libraries are byte-identical
to4.1.0, and all ELF LOAD segment alignments are at least16KiB. None of these
static gates establishes physical installation or audible output on HONOR.

AppId: com.alienware377.viper4android.rootless. Test signer:
CN=Android Debug,O=Android,C=US; certificate SHA256
 dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675.
ARM64 version140 SHA256:
893a945370e61322dbe3473eb692873f287e05730101c58ac1bad7dced93624c.
Rollback141 SHA256:
b88a1e9fcb220c2bec4f23e8fc158cd702b916f70235451eb3a3f9031ef7b7b1.
Rollback source5b6f5c9 built successfully and passed the normal-installer static
gates; it retains the older4.0.7 runtime with version metadata changed. Actual
rollback installation on HONOR remains untested. Prior upstream signing-key
compatibility is unknown; no destructive uninstall/data clearing is authorized.

## Failed integration run retained

Run39613454418:27/30 passed, three failures. Both new guard tests used exitValue()
while the process was alive; Shizuku Binder reconstructed IllegalThreadStateException
as IllegalArgumentException. The tests now use its dedicated alive() API. The
existing digital-zero test restored baseline but failed its5000ms observation
bound at5027ms. Its repeating1.48s measurement windows quantized observation;
continuous50ms sampling now requires three consecutive baseline matches, with
the original5000ms assertion unchanged. Product DSP and the signed ARM64 APK
were not modified to address these instrumentation failures. This is a change
in measurement, not a claim of improved physical audio recovery latency.

The corrected instrumentation passed in run39614979983. Duplicate manual
run39614981994 was cancelled. Executed evidence and measurement limits follow.

## Final executed integration result —2026-10-21

https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39614979983
Source/test snapshot dee6ef4, product runtime c0b9a2c.30/30 instrumentation tests
passed:5 native recovery,14 native effect measurements,8 complete routed engine
scenarios,2 blocked policy-worker tests,1 legacy lifecycle test. Workflow gates
found no remaining routing/fault workers and no fatal crash. The duplicate manual
run39614981994 completed CANCELLED. No further CI execution is needed for the
unchanged runtime.

The independent synthetic PCM meter confirmed signal returning within250mB
(2.5dB) of original baseline on three consecutive50ms samples. Observations:
forced DSP exception551ms; renderer lease loss2070ms; digital-zero DSP3648ms;
normal stop556ms; AAudio44100 client stop1398ms. The AAudio client implements
correct stream reopen; this is not proof of Spotify behavior. Measurements
include meter latency and are not physical acoustic recovery guarantees.

The intentionally stuck cleanup worker exited on the independent guard, with
baseline=-4951mB and restored=-4951mB, overall observation2194ms. The intentionally
stuck initialization worker also exited, baseline=-4951mB and restored=-4951mB,
overall observation6020ms. These latter windows include process startup, the
initialization deadline where applicable, and a1.48s meter acquisition window;
they must not be presented as just the cleanup deadline or end-to-end latency.

No real HONOR, Spotify, Bluetooth, screen-lock or60minute acceptance was performed.
All entries in DEVICE-ACCEPTANCE.csv remain PENDING. The pre-existing negative
AAudio scenario (a client ignoring disconnection) remains a compatibility
boundary, not a passed Spotify recovery test. Do not call this a confirmed
Spotify repair until that application's real playback and recovery are observed.
