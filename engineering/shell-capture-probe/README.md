# Full-engine capture feasibility, Android 16

This branch does not change the delivered application. It tests one previously
unverified alternative: explicit user-authorized shell audio routing, at 48 kHz
stereo, restricted to a separate synthetic source application UID.

No source capture policy is changed. No permission is granted to the ordinary
application. No root, UID impersonation, framework patch, or app-ops override is
used. `app_process` executes with the actual ADB shell UID and package attribution.

AOSP Android 16 distinguishes:

* `LOOP_BACK|RENDER`: copies playback; honors NO_MEDIA_PROJECTION unless privileged
  capture is requested. Privileged capture is limited to mono, <=16 kHz.
* `LOOP_BACK`: redirects the matched playback using MODIFY_AUDIO_ROUTING. This is
  a different policy path, intended for explicitly privileged audio routing.

Sources reviewed (android16-release):

* frameworks/base/packages/Shell/AndroidManifest.xml
* frameworks/base/media/java/android/media/audiopolicy/AudioMix.java
* frameworks/base/media/java/android/media/audiopolicy/AudioPolicy.java
* frameworks/base/services/core/java/com/android/server/audio/AudioService.java
* frameworks/av/services/audiopolicy/common/managerdefinitions/src/AudioPolicyMix.cpp

The test first runs the copy-mode negative control on an ALLOW_CAPTURE_BY_SYSTEM
synthetic source, then tests the routing mode at 48000 Hz stereo PCM16. Each run
lasts five seconds and releases its recorder and policy in finally. CI additionally
bounds the process to twenty seconds. This is not a production backend: it has no
replacement output and temporarily redirects only disposable synthetic audio.

Success would establish only emulator API feasibility. Production integration
would additionally require output/DSP transport, binder-death restoration tests,
no-feedback UID rules, route/recovery tests and real HONOR/Spotify validation.
No claim that all effects work on Spotify follows from this probe.
