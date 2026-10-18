# Executed evidence

## Run 39110841519 — failed probe, no capture conclusion

https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39110841519

Android 16 Google APIs x86_64, actual ADB shell UID 2000:

```
permission CAPTURE_AUDIO_OUTPUT=0
permission CAPTURE_MEDIA_OUTPUT=0
permission MODIFY_AUDIO_ROUTING=0
permission RECORD_AUDIO=0
register=0 routeFlags=3
```

Here 0 means PERMISSION_GRANTED. Registration succeeded. The initial recorder
failed AudioFlinger attribution validation: UID 2000 was paired with package
`android` by the context-free policy helper. The probe also incorrectly stopped
an uninitialized recorder in finally; that masked the initial error. Therefore
neither capture-mode assertion ran and this is not evidence for or against
full-quality capture.

Commit 57e93c2 supplies the actual shell package Context to AudioRecord.Builder;
it does not change UID or permission checks. Cleanup now unregisters policy even
if recorder shutdown throws and avoids stopping an uninitialized recorder.

## Run 39111050483 — failed probe, cleanup verified

https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39111050483

Policy registration succeeded, and cleanup completed after recorder creation
failed. Creating a shell package Context alone retained the parent system
Context's `android` attribution. The synthetic AudioTrack also failed native
permission-controller UID validation immediately after first boot/install.
Neither mode's signal assertion executed. No capture success is claimed.

## Run 39111243706 — PASS, API feasibility only

https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39111243706

Tested commit: 375f30e. Android 16 Google APIs x86_64 emulator. No root, no
source-policy change and no permission/app-ops override. The shell Context
explicitly reports its actual UID 2000 and actual package com.android.shell.
The source was required to stay alive before either measurement.

| Mode | Rate | Channels | Captured frames | Nonzero left frames | RMS |
| --- | --- | --- | --- | --- | --- |
| Ordinary copy (3), negative control | 48000 | 2 | 239616 | 0 | 0.0 |
| Privileged routing (2) | 48000 | 2 | 239616 | 237558 | 0.1408156 |

Both policy registrations returned 0. Both cleanup sequences completed.
The same separate-UID synthetic source used ALLOW_CAPTURE_BY_SYSTEM.

The positive run negotiated stereo and recovered the expected amplitude from
its two-identical-channel synthetic source. This does not prove independent
left/right channel fidelity, measured audible output, spectral response, DSP
processing, binder-death restoration, Spotify compatibility or HONOR behavior.
Frequency estimated over the entire capture including startup zeros was
988.58 Hz for the 997 Hz source; that whole-window value is not a timing-drift
measurement.

### Engineering implication

The earlier blanket assumption that rootless full-quality capture is impossible
for a NO_MEDIA_PROJECTION source was too broad. Explicitly authorized shell
routing provides a tested potential PCM input to the full native engine.
This is not yet an integrated application fix. Production integration must
bound routing lifetime, release on app/process/transport death, prevent output
feedback, enforce source and usage selection, preserve stereo framing and
validate native effects and route recovery before an owner APK is delivered.
