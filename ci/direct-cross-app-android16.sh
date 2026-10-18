#!/usr/bin/env bash
set -euo pipefail
mkdir -p cross-app-evidence
trap 'adb logcat -b all -d > cross-app-evidence/logcat.txt; adb shell dumpsys media.audio_flinger > cross-app-evidence/audio-flinger.txt; adb shell dumpsys audio > cross-app-evidence/audio.txt' EXIT
adb install test-fixtures/cross-app/previous-app.apk | tee cross-app-evidence/previous-install.txt
adb install -r test-fixtures/cross-app/app.apk | tee cross-app-evidence/candidate-update.txt
for file in instrumentation source-one source-two; do
  adb install -t "test-fixtures/cross-app/$file.apk"
done
adb install /tmp/vlc.apk
adb install /tmp/mpv.apk
pkg=com.alienware377.viper4android.rootless
adb shell pm grant "$pkg" android.permission.DUMP
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
run_test() {
  cls=$1
  expected=$2
  adb shell am instrument -w -r -e class "me.timschneeberger.rootlessjamesdsp.interop.$cls" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "cross-app-evidence/$cls.txt"
  adb logcat -d -s DirectCrossAppTest:I DirectSessionEffectTest:I | tee cross-app-evidence/measurements.txt
  grep -q "OK ($expected test" "cross-app-evidence/$cls.txt"
}
run_test NativeAudioRecoveryTest 5
run_test DirectSessionEffectTest 1
run_test DirectCrossAppTest 4
adb shell pm revoke "$pkg" android.permission.RECORD_AUDIO
run_test DirectServiceLifecycleTest 1
if adb logcat -b crash -d | grep -E 'FATAL EXCEPTION|Fatal signal [0-9]+'; then exit 1; fi
