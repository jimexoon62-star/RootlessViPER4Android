#!/usr/bin/env bash
set -euo pipefail
mkdir -p startup-evidence
pkg=com.alienware377.viper4android.rootless
activity=$pkg/me.timschneeberger.rootlessjamesdsp.activity.MainActivity
apk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-x86_64-release.apk' | head -1)
testapk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-release-androidTest.apk' | head -1)
test -f "$apk"
trap 'adb shell am force-stop org.rv4a.captureprobe; adb logcat -b all -d > startup-evidence/all-logcat.txt; adb shell dumpsys activity activities > startup-evidence/activities.txt; adb shell screencap -p /sdcard/startup.png; adb pull /sdcard/startup.png startup-evidence/startup.png || true' EXIT
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

# Separate synthetic source. Consent is accepted through the real system UI,
# never by changing projection app-ops or privileged capture restrictions.
probeapk=$(find audio-capture-probe/build -path '*/apk/*' -name 'audio-capture-probe-debug.apk' | head -1)
test -f "$probeapk"
adb install -r "$probeapk"
adb shell pm grant org.rv4a.captureprobe android.permission.RECORD_AUDIO
adb shell am start -W -n org.rv4a.captureprobe/.ProbeActivity
accepted=false
for attempt in $(seq 1 15); do
  adb shell uiautomator dump /sdcard/probe-consent.xml >/dev/null
  adb pull /sdcard/probe-consent.xml startup-evidence/probe-consent.xml >/dev/null
  point=$(python3 - <<'PY'
import xml.etree.ElementTree as E,re
for n in E.parse('startup-evidence/probe-consent.xml').iter('node'):
    if n.get('resource-id')=='android:id/button1' and n.get('enabled')=='true' and n.get('package')=='com.android.systemui':
        x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
        print((x1+x2)//2,(y1+y2)//2)
        break
PY
)
  if [ -n "$point" ]; then adb shell input tap $point; accepted=true; break; fi
  sleep 1
done
[ "$accepted" = true ]
for attempt in $(seq 1 40); do
  if adb shell run-as org.rv4a.captureprobe cat files/result.json > startup-evidence/probe-result.json 2>/dev/null; then break; fi
  sleep 1
done
python3 - <<'PY'
import json
with open('startup-evidence/probe-result.json') as f: r=json.load(f)
assert r.get('complete'),r
for name in ['allowed','allowedAgain']:
    s=r[name]
    assert s['rms']>.01 and s['samples']>48000 and s['head']>48000,(name,s)
s=r['systemOnly'];assert s['samples']>48000 and s['rms']<.00001,s
print('CAPTURE_PROBE:', {k:{j:v for j,v in r[k].items() if j!='pcm'} for k in ['allowed','systemOnly','allowedAgain']})
PY
adb push startup-evidence/probe-result.json /data/local/tmp/rv4a-probe.json
adb shell am instrument -w -r -e class me.timschneeberger.rootlessjamesdsp.interop.CapturedSignalTest -e probeResultPath /data/local/tmp/rv4a-probe.json "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee startup-evidence/captured-signal-instrumentation.txt
grep -q 'OK (1 test)' startup-evidence/captured-signal-instrumentation.txt
adb shell am force-stop org.rv4a.captureprobe

# Public session effect on a self-owned source which opts out of MediaProjection.
# Only the normal RECORD_AUDIO test permission is granted; no capture-policy override.
adb shell pm grant "$pkg" android.permission.RECORD_AUDIO
adb shell am instrument -w -r -e class me.timschneeberger.rootlessjamesdsp.interop.DirectSessionEffectTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee startup-evidence/direct-session-instrumentation.txt
grep -q 'OK (1 test)' startup-evidence/direct-session-instrumentation.txt
adb logcat -d -s DirectSessionEffectTest:I > startup-evidence/direct-session-measurement.txt
cat startup-evidence/direct-session-measurement.txt

adb shell pm grant "$pkg" android.permission.DUMP
adb shell am instrument -w -r -e class me.timschneeberger.rootlessjamesdsp.interop.DirectServiceLifecycleTest "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee startup-evidence/direct-lifecycle-instrumentation.txt
grep -q 'OK (1 test)' startup-evidence/direct-lifecycle-instrumentation.txt
