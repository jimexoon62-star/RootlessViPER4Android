# Rootless audio blackout remediation candidate

Status: engineering candidate; initial 49 JVM tests and ARM64 build/ELF inspection passed. Final frozen-source build evidence is recorded separately. Target-device acceptance pending.

## Evidence and causation

Source baseline is the installed revision d69429bc892da05bd4b39d614c83b2750592b7f2 (v4.0.0/127). Work is isolated on fix/rootless-audio-blackout-honor-android16 in the authorized jimexoon62-star fork. Upstream is read-only. No clinic repository was accessed or modified.

The supplied October 16 dump ends with `NOTE: Log file does not exist`. It lacks application exceptions and worker stage measurements needed to identify the initiating event. Therefore no exact root cause is claimed.

Relevant sequence (device clock):

- 08:55:19: the same cumulative underrun count 155430 appears across a track remove/add. It is not an interval rate and precedes the later teardown by eight minutes.
- 09:03:16.752: first capture policy registration; replacement AudioTrack created at 09:03:16.798.
- 09:03:17.581: second policy registration, followed by first-policy unregistration and replacement track stop/start. This is consistent with recorder recreation; it does not prove abnormal policy removal.
- 09:03:39.569: second-policy unregistration; remote submix input/output patches released; replacement track stopped at .829 and released at .853.
- 09:04:05.948: Spotify creates a new AAudio session; flags 0x0; stereo 44.1 kHz, routed to the Bluetooth output. No subsequent ViPER reattachment is demonstrated before the dump ends.

AAudio is not evidence of an MMAP/exclusive path here. Histen, power-management messages and policy unregistration are not established initiating causes. A transient per-app volume/mute event also appears in the framework history; the dump does not establish its causal relationship to the failure. No buffer-size or scheduling performance improvement is claimed without measurements.

Verified source defects: discarded AudioRecord/AudioTrack transfer counts, an ignored IOException that exits the worker while the service survives, MediaProjection revocation ignored in the foreground, concurrent session modifications, callbacks that re-enable mute effects during teardown, and a timed join allowing overlapping workers/native-engine closure.

Prior fork branches were inspected: fix/audio-failopen-debug-20261012, repair/audio-recovery, repair/audio-recovery-evidence. Earlier reported target silence is retained as an unresolved finding. Prior emulator results are historical, not test evidence for this candidate. Reviewed carry-forwards include JNI bounds/allocation checks, APK-only native target selection, persistent private diagnostic export, and the native regression test source. Rooted HAL/Magisk changes are deliberately excluded.

## Changes and safety mechanism

- Effect ownership starts disabled. Only successful processing allows mute attachment. All callback mutations and retirement share an identity-based ownership monitor. Retirement removes ownership before detaching listeners, disabling, and releasing. Old callbacks cannot regain ownership after a restart. Listeners no longer re-enable muting at all.
- Fatal failure atomically marks the current worker failed and releases session muting synchronously before asynchronous teardown/retry. New sessions are refused while suspended; session database mutation/destruction is serialized. Duplicate session discovery cannot overwrite an unreleased effect.
- Each listener-detach, disable, and native release operation is independent. Disable return codes and release exceptions are logged. Release has one bounded immediate retry. If both platform release calls fail, release remains explicitly unconfirmed; source code cannot guarantee behavior of a broken platform API.
- A worker completes each interleaved stereo block using checked nonblocking reads/writes, preserving offsets across partial transfers. Negative, oversized and unaligned counts fail. Cancellation and zero-transfer stalls are bounded. Only fully read data reaches DSP. Float output is prefilled with NaN to expose unwritten output, and non-finite data fails before playback.
- Successful silent capture does not prove useful replacement audio. After two seconds of continuous silent input, mute authorization expires without treating silence as an error or restarting capture. Muting can resume only after capture/DSP/write progress. Ordinary paused/silent content remains direct and capture continues listening.
- Meaningful input followed by exactly zero DSP output for three seconds fails open. A user profile intentionally forcing digital zero can therefore cause bypass; this is a deliberate audible-playback safety preference.
- A 500 ms watchdog checks full-iteration progress, frame counters and output playback-head/underrun deltas. It does not equate an active track with audible playback. No-progress threshold is 3 s; stationary output with increasing underruns across six checks also fails. Silence-gated or session-idle output is excluded.
- One automatic recovery attempt per service lifetime, with 500 ms backoff and original muting released. No retry resets within the same service lifetime. Failure after exhaustion requires explicit user activation. Revoked MediaProjection is terminal and discards authorization; it is never retried.
- Track routing callbacks detect speaker/Bluetooth selection changes even when both devices remain connected; initial routing never triggers a rebuild. Device changes stop/join/flush old resources and debounce rebuilding by 250 ms; more than four change callbacks within 30 s falls back to direct audio. These are conservative safety thresholds requiring physical tuning.
- A still-alive old worker prevents recreation and native-engine closure. The old generation cannot re-arm muting. SDK AudioRecord owns playback-capture policy registration; its release and MediaProjection cleanup are the balanced teardown mechanism. No manual hidden AudioPolicy registration is added.
- Capture/output byte allocations use platform minimum sizes and stereo-frame alignment. DSP block length, sample rate and PCM format remain matched. Both 44.1/48 kHz remain supported; no latency or CPU improvement is asserted.
- Existing private settings/profile storage is retained. No new telemetry, account, root requirement or permissions. FOSS release flavor is used. Native default ABI support is preserved; delivery selects ARM64 using the supported Gradle build ABI property.

## Limits of validation

Unit tests exercise production transfer, signal lease, DSP-output integrity and mute-ownership code, including races and database lifecycle with mocked Android effects. They cannot establish actual DynamicsProcessing release, audibility, Android policy balance, OEM routing or Bluetooth recovery. Framework MediaProjection revocation and real service death remain integration/device checks unless separately recorded as executed. No emulator run from a prior branch is counted here.

The watchdog depends on Android scheduling and calls; its thresholds are detection goals, not a hard real-time audible-restoration guarantee. Native DSP stalls can leave a thread alive; original effects are retired first and duplicate workers are refused. Successful nonzero input/output and advancing playback head still cannot prove physical audibility. Real-device acceptance is mandatory.
