#!/usr/bin/env bash
set -euo pipefail
mkdir -p cross-app-evidence
trap 'adb logcat -b all -d > cross-app-evidence/logcat.txt; adb shell dumpsys media.audio_flinger > cross-app-evidence/audio-flinger.txt; adb shell dumpsys audio > cross-app-evidence/audio.txt' EXIT
for file in app instrumentation source-one source-two; do
  adb install "test-fixtures/cross-app/$file.apk"
done
pkg=com.alienware377.viper4android.rootless
adb shell pm grant "$pkg" android.permission.DUMP
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -r -e class me.timschneeberger.rootlessjamesdsp.interop.DirectCrossAppTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee cross-app-evidence/result.txt
grep -q 'OK (2 tests)' cross-app-evidence/result.txt
adb logcat -d -s DirectCrossAppTest:I | tee cross-app-evidence/measurements.txt
if adb logcat -b crash -d | grep -E 'FATAL EXCEPTION|Fatal signal [0-9]+'; then exit 1; fi
