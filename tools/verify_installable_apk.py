#!/usr/bin/env python3
"""Static APK delivery gate; does not claim a successful Android installation."""
import argparse
import pathlib
import subprocess
import sys
import zipfile

p = argparse.ArgumentParser()
p.add_argument('apk', type=pathlib.Path)
p.add_argument('--build-tools', required=True, type=pathlib.Path)
a = p.parse_args()
manifest = subprocess.check_output([str(a.build_tools / 'aapt'), 'dump', 'xmltree', str(a.apk), 'AndroidManifest.xml'], text=True)
for line in manifest.splitlines():
    if 'android:testOnly(' in line and not line.rstrip().endswith('0x0'):
        sys.exit('REJECTED: testOnly APK cannot be installed through the normal package installer')
subprocess.run([str(a.build_tools / 'apksigner'), 'verify', str(a.apk)], check=True)
subprocess.run([str(a.build_tools / 'zipalign'), '-c', '-P', '16', '4', str(a.apk)], check=True)
with zipfile.ZipFile(a.apk) as z:
    if z.testzip() is not None:
        sys.exit('REJECTED: damaged ZIP entry')
print('PASS: testOnly is absent/false, signature verifies, ZIP integrity and alignment verified.')
print('Physical installation and audible playback remain unverified.')
