#!/usr/bin/env bash
set -euo pipefail
mkdir -p startup-evidence
pkg=com.alienware377.viper4android.rootless
activity=$pkg/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
apk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-x86_64-release.apk' | head -1)
testapk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-release-androidTest.apk' | head -1)
test -f "$apk"
trap 'adb logcat -b all -d > startup-evidence/all-logcat.txt; adb shell dumpsys activity activities > startup-evidence/activities.txt; adb shell screencap -p /sdcard/startup.png; adb pull /sdcard/startup.png startup-evidence/startup.png || true' EXIT
launch() {
  label=$1
  adb shell am force-stop "$pkg"
  adb logcat -c
  adb shell am start -W -n "$activity" > "startup-evidence/$label-launch.txt"
  sleep 10
  adb logcat -b all -d > "startup-evidence/$label-logcat.txt"
  adb shell dumpsys activity activities > "startup-evidence/$label-activities.txt"
  adb shell pidof "$pkg" > "startup-evidence/$label-pid.txt"
  if grep -E 'FATAL EXCEPTION|Fatal signal [0-9]+' "startup-evidence/$label-logcat.txt"; then return 1; fi
  grep -E 'mResumedActivity|topResumedActivity' "startup-evidence/$label-activities.txt" | grep "$pkg"
}
adb install /tmp/rv4a-original.apk | tee startup-evidence/original-install.txt
launch original-no-permissions
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell pm grant "$pkg" android.permission.DUMP
launch original-with-permissions
adb install -r "$apk" | tee startup-evidence/update-install.txt
launch candidate-update-with-permissions
adb shell pm revoke "$pkg" android.permission.RECORD_AUDIO
launch candidate-update-without-record-permission
# Fresh installation in this disposable emulator only; never an owner device.
adb uninstall "$pkg"
adb install "$apk" | tee startup-evidence/fresh-install.txt
launch candidate-fresh-no-permissions
adb install -r "$testapk"
adb shell am instrument -w -r -e class me.timschneeberger.rootlessjamesdsp.interop.NativeAudioRecoveryTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee startup-evidence/native-instrumentation.txt
grep -q 'OK (' startup-evidence/native-instrumentation.txt
