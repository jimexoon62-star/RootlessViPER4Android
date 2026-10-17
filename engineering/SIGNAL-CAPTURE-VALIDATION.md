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
