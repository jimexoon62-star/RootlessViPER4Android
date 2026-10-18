#!/usr/bin/env bash
set -euo pipefail
mkdir -p shell-evidence
trap 'adb logcat -b all -d > shell-evidence/logcat.txt; adb shell dumpsys media.audio_policy > shell-evidence/policy.txt; adb shell dumpsys media.audio_flinger > shell-evidence/flinger.txt' EXIT
adb install -t test-fixtures/shell-capture/source.apk
adb push test-fixtures/shell-capture/probe.jar /data/local/tmp/rv4a-shell-probe.jar
uid=$(adb shell cmd package list packages -U org.rv4a.captureprobe | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | tr -d '\r')
[[ "$uid" =~ ^[0-9]+$ ]]
for flags in 3 2; do
  adb shell am force-stop org.rv4a.captureprobe
  adb shell am start -n org.rv4a.captureprobe/.DirectToneActivity
  sleep 2
  timeout 20 adb shell "CLASSPATH=/data/local/tmp/rv4a-shell-probe.jar app_process /system/bin ShellCaptureProbe $uid $flags" | tee "shell-evidence/route-$flags.txt"
  grep -q 'CLEANUP complete' "shell-evidence/route-$flags.txt"
done
adb shell am force-stop org.rv4a.captureprobe
