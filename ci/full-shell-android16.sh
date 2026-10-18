#!/usr/bin/env bash
set -euo pipefail
mkdir -p full-shell-evidence
trap 'adb logcat -b all -d > full-shell-evidence/logcat.txt; adb shell dumpsys media.audio_policy > full-shell-evidence/audio-policy.txt; adb shell dumpsys media.audio_flinger > full-shell-evidence/audio-flinger.txt; adb shell "cat /data/local/tmp/full-relay-*.log" > full-shell-evidence/relay-logs.txt || true' EXIT
(cd test-fixtures/full-shell && sha256sum -c SHA256SUMS)
adb install test-fixtures/full-shell/previous-app.apk
adb install -r test-fixtures/full-shell/app.apk
adb install -t test-fixtures/full-shell/instrumentation.apk
adb install -t test-fixtures/shell-capture/source.apk
adb push test-fixtures/full-shell/app.apk /data/local/tmp/full-app.apk
adb push test-fixtures/full-shell/relay.jar /data/local/tmp/full-relay.jar
pkg=com.alienware377.viper4android.rootless
adb shell pm grant "$pkg" android.permission.DUMP
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
sleep 20 # Allow first-boot native package/permission cache population.
run_test() {
  cls=$1
  expected=$2
  adb shell am instrument -w -r -e class "me.timschneeberger.rootlessjamesdsp.interop.$cls" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "full-shell-evidence/$cls.txt"
  grep -q "OK ($expected test" "full-shell-evidence/$cls.txt"
}
run_test NativeAudioRecoveryTest 5
run_test FullNativeEffectsTest 14
run_test FullShellEngineTest 4
run_test DirectServiceLifecycleTest 1
adb shell ps -A -o ARGS > full-shell-evidence/processes.txt
if grep -q 'app_process /system/bin ShellTestRelay' full-shell-evidence/processes.txt; then echo 'Leaked routing process'; exit 1; fi
if adb logcat -b crash -d | grep -E 'FATAL EXCEPTION|Fatal signal [0-9]+'; then exit 1; fi
