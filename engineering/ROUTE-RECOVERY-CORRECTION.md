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

Validation is in progress. The Android 16 CI startup/upgrade/native test is distinct from physical HONOR/Spotify playback acceptance. The first CI attempt failed before building because setup-android requested the removed `tools` SDK package; its configuration was corrected to request platform-tools. No tests from that failed setup are counted as passed.
