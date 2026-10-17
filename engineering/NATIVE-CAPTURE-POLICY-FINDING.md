# Native capture restriction found in owner follow-up

The previous follow-up report's statement that the cause was wholly unresolved was incomplete: it considered public playback flags but did not correlate the native client attributes. This finding supersedes that interpretation.

## Reproducible evidence (normalized; owner dump stays private)

The public player is AAudio, started, MEDIA, flags `0x0`. Its same session/UID in native audio policy has `Flags: 0x8600`, regular PCM16 stereo 44100 output; its output flags are `0x8` (DEEP_BUFFER). This establishes neither MMAP nor exclusive hardware output.

`0x8600 & 0x400 = 0x400`. Android 16 AudioAttributes defines FLAG_NO_MEDIA_PROJECTION as `1 << 10`; AudioPolicyMix::mixMatch rejects that flag for ordinary MediaProjection mixes, unless privileged capture is explicitly authorized. The app's registered mix has privileged playback capture disabled. The native client has no secondary capture output, whereas other eligible clients do. The capture sink is in standby with zero total writes/frames written, while its recording reader continues receiving frames. This directly explains how successful reads can consist of zeros despite Spotify playing audibly.

An application manifest `allowPlaybackCapture=true`, public player flags zero, or a recorder marked "not silenced" does not override native per-track restrictions. The dump does not establish whether Spotify, AAudio/framework policy, or an OEM component supplied the effective flag. No such attribution is claimed.

AOSP Android 16 source:
- https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/media/java/android/media/AudioAttributes.java
- https://android.googlesource.com/platform/frameworks/av/+/refs/heads/android16-release/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp

## Implementation

Parse native client capture flags separately from output flags. Preserve restrictions conservatively across duplicate active/client rows. Native policy data supplements whichever public session detector is selected. A restricted session cannot obtain a new mute, including through direct session callbacks; discovering a new restriction releases an existing mute immediately. These mandatory restrictions are independent of the optional "exclude restricted apps" preference. The capture-signal lease now also refuses initial muting until nonzero input has actually been observed; zero-only successful reads cannot authorize even the former two-second startup suppression. No privileged bypass, security change, or source-app modification is introduced. Native-engine deferred retirement from the preceding change is included.

This is a safety/diagnosis build. It does not promise processed Spotify output for a native track that prohibits MediaProjection. The supplied snapshot establishes that this particular track is excluded under ordinary Android 16 policy. It does not establish that all Spotify tracks/devices/versions permanently prohibit capture.

## Validation scope

Unit fault tests cover native/public disagreement, both capture bits, noncapture flags, malformed input, duplicate rows, repeated suspend/resume, immediate mute release on restriction, late session callbacks, and UID reuse. Build and test results are recorded separately. No physical device validation has been executed for this revision.

Executed results: the ARM64 release built successfully in 2m52s; 69 JVM tests passed with zero failures/errors/skips. The compiled production PlaybackCapturePolicy class was also invoked directly on the owner's private dump: it reproduced target native flags `0x8600` and `projection restricted=true`. The input dump and its session/UID identifiers were not published. Actual browser-downloaded APK SHA-256: `9669447e29057f1b80b2decc8db9869b4c90c9d49888e93dc9bf22ab20d1ebf6`. Static installation/signature/alignment and native ABI/dependency/export gates passed. Android 16 x86_64 startup/update/native instrumentation CI: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39013314754 (not counted as passed while running).
