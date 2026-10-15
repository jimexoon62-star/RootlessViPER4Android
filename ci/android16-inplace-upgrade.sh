#!/usr/bin/env bash
set -euo pipefail

PKG=com.alienware377.viper4android.rootless
UPDATE=$(find update-apks -type f -name '*rootless-fdroid-x86_64-release.apk' | head -n 1)
test -n "$UPDATE" && test -s "$UPDATE"
test -s official.apk

adb logcat -c
adb install -r official.apk
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO
adb shell dumpsys package "$PKG" > before-package.txt
if ! grep -q 'android.permission.RECORD_AUDIO: granted=true' before-package.txt; then
  echo 'FAIL: cannot grant audio permission for baseline.' >&2; exit 3
fi

# Critical: no adb uninstall or pm clear. This tests the actual upgrade path.
adb install -r "$UPDATE"
adb shell dumpsys package "$PKG" > after-package.txt
adb shell pm path "$PKG"
grep -F 'versionCode=128' after-package.txt | head -n 1
if ! grep -q 'versionCode=128' after-package.txt; then
  echo 'FAIL: update version code not installed' >&2; exit 4
fi
if ! grep -q 'android.permission.RECORD_AUDIO: granted=true' after-package.txt; then
  echo 'FAIL: previous permission not retained across update' >&2; exit 5
fi
adb shell am start -W -n "$PKG/me.timschneeberger.rootlessjamesdsp.activity.MainActivity" | tee update-launch-result.txt
sleep 12
adb shell dumpsys activity activities > update-activity-dump.txt || true
adb shell logcat -d -v threadtime > update-launch-logcat.txt || true
adb exec-out screencap -p > update-screen.png || true
echo 'Foreground:'
grep -E 'topResumedActivity|ResumedActivity' update-activity-dump.txt | head -n 4 || true
echo 'Process:'
adb shell pidof "$PKG"
echo 'Crash excerpts:'
grep -Ei 'FATAL EXCEPTION|Process: com.alienware377.viper4android.rootless|Fatal signal' update-launch-logcat.txt | tail -n 20 || true
echo 'PASS: Original package updated in place to v128 with RECORD_AUDIO permission retained and startup process alive. Real HONOR audio not tested.'
