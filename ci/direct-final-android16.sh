#!/usr/bin/env bash
set -euo pipefail
mkdir -p startup-evidence
pkg=com.alienware377.viper4android.rootless
trap 'adb logcat -b all -d > startup-evidence/all-logcat.txt; adb shell dumpsys activity services "$pkg" > startup-evidence/services.txt; adb shell dumpsys media.audio_flinger > startup-evidence/audio-flinger.txt' EXIT
adb install /tmp/rv4a-original.apk | tee startup-evidence/original-install.txt
adb install -r test-fixtures/direct-final/app-x86_64.apk | tee startup-evidence/candidate-update.txt
adb install test-fixtures/direct-final/instrumentation.apk
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.DUMP
adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
run_test() {
    cls=$1
    expected=$2
    adb shell am instrument -w -r -e class "me.timschneeberger.rootlessjamesdsp.interop.$cls" "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "startup-evidence/$cls.txt"
    grep -q "OK ($expected test" "startup-evidence/$cls.txt"
}
run_test NativeAudioRecoveryTest 5
run_test DirectSessionEffectTest 1
adb logcat -d -s DirectSessionEffectTest:I > startup-evidence/direct-session-measurement.txt
cat startup-evidence/direct-session-measurement.txt
# Production direct mode must work without microphone/projection privileges.
adb shell pm revoke "$pkg" android.permission.RECORD_AUDIO
run_test DirectServiceLifecycleTest 1
if adb logcat -b crash -d | grep -E 'FATAL EXCEPTION|Fatal signal [0-9]+'; then exit 1; fi
