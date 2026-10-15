#!/usr/bin/env bash
set -euo pipefail

PKG=com.alienware377.viper4android.rootless.debug
APK=ci-apks/ViPER4Android-v4.0.0-rootless-fdroid-x86_64-debug.apk

test -s "$APK"
adb logcat -c
adb install -r "$APK"
adb shell pm path "$PKG"
adb shell am start -W -n "$PKG/me.timschneeberger.rootlessjamesdsp.activity.MainActivity" | tee launch-result.txt
sleep 15
adb shell dumpsys activity activities > activity-dump.txt || true
adb shell logcat -d -v threadtime > launch-logcat.txt || true
adb exec-out screencap -p > launch-screen.png || true
adb shell uiautomator dump /sdcard/window.xml || true
adb shell cat /sdcard/window.xml > window.xml || true
echo "package process:"
adb shell pidof "$PKG" || true
echo "foreground activity:"
grep -E 'mResumedActivity|topResumedActivity|ResumedActivity' activity-dump.txt | head -n 10 || true
echo "fatal and ANR reports:"
grep -Ei 'FATAL EXCEPTION|SIGSEGV|SIGABRT|Process: com.alienware377.viper4android.rootless.debug|ANR in' launch-logcat.txt | tail -n 60 || true
if grep -Eq 'Cannot launch application. Please re-download|Launch error [123]' window.xml launch-logcat.txt; then
  echo 'FAIL: built-in app launch guard rejected debug APK' >&2
  exit 10
fi
if grep -E 'FATAL EXCEPTION|Fatal signal|ANR in' launch-logcat.txt | grep -q "$PKG"; then
  echo 'FAIL: Android crashed the patched app' >&2
  exit 11
fi
if ! adb shell pidof "$PKG" >/dev/null; then
  echo 'FAIL: Application process not running 15 seconds after start' >&2
  exit 12
fi
echo 'PASS: Android 16 emulator installed and launched the APK. Audio and HONOR hardware remain untested.'
