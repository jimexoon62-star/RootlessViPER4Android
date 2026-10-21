# Rootless ViPER 4.1.1 — bounded policy-owner lifetime

Status: testing in progress. HONOR/Spotify physical acceptance remains unexecuted.
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
and synthetic source baseline restoration. These tests must be executed before
this section is marked passed.

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
Android30-scenario run is pending; no claim of passing it has been made.
