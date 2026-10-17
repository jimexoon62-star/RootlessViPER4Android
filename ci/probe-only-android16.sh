#!/usr/bin/env bash
set -euo pipefail
mkdir -p startup-evidence
pkg=com.alienware377.viper4android.rootless
apk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-x86_64-release.apk' | head -1)
testapk=$(find app/build -path '*/apk/*' -name '*rootless-fdroid-release-androidTest.apk' | head -1)
trap 'adb shell am force-stop org.rv4a.captureprobe; adb logcat -b all -d > startup-evidence/all-logcat.txt; adb shell dumpsys media.audio_policy > startup-evidence/audio-policy.txt' EXIT
adb install "$apk"
adb install "$testapk"
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
