# Owner device follow-up: 4.0.4 / 131

The owner reports audible Spotify playback with no clear ViPER effect. This is not an audio-processing acceptance pass.

The supplied follow-up dump identifies runtime b590c4d. The previous route-change restart loop is absent. During the new playback runs, logged capture/output peaks remain zero while frame counts advance. These periodically sampled logs cannot establish every intervening sample or end-to-end audibility. Spotify is started on a MEDIA session matching capture rules; recording is reported not silenced. AAudio flags shown are zero, which does not establish MMAP/exclusive playback. The cause of zero captured signal remains unresolved; OEM interference is not demonstrated.

The capture-silence lease releases suppression after two seconds of silent buffers, consistent with direct playback being audible. This does not establish functioning DSP.

Projection revocation exposed a separate confirmed defect: the worker remained alive past the 500 ms join deadline, then exited roughly one second after revocation. Destruction retained native state without a deferred release. WorkerRetirement now defers exactly one engine close until worker cleanup has completed, including when destruction and worker exit race. Intentional shutdown I/O errors no longer initiate failure recovery. Restart still refuses overlapping workers. An unresponsive worker indefinitely inside native/platform code still prevents safe freeing of the native state; source suppression remains released.

No new physical-device test has been executed for this change. No assertion is made that it repairs the zero-input capture path. Existing installed 4.0.4 must not be described as a completed remediation.

Validation: `python engineering/build.py -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false :app:testRootlessFdroidReleaseUnitTest :app:assembleRootlessFdroidRelease` completed successfully. The suite reports 60 tests, zero failures/errors/skips, including four WorkerRetirement tests (delayed exit, normal restarts, 100 simultaneous-exit/destruction races, and retired-owner rejection). A final unit-test rerun covers the additional disposal-timeout logging branch. This is JVM adapter/lifecycle evidence, not physical audio validation. The assembled intermediate APK is not being offered as a fix for the unresolved Spotify capture problem.
