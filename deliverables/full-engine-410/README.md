# Experimental full native engine build — NOT a confirmed Spotify fix

Implementation source: e985548, fix/full-shell-viper-engine.
Requires genuine Shizuku ADB-mode authorization. No root needed.

Read ENGINEERING-REPORT.md and STATUS-AR.md before installation.
Physical HONOR/Spotify validation remains outstanding. A nonrecovering AAudio
source can remain disconnected after routing release; this critical limitation
prevents classifying the repair as complete.

The APK is test-signed, package com.alienware377.viper4android.rootless, code138.
Use only an in-place update accepted by Android with the same signing identity.
Never uninstall or clear data to bypass a signature mismatch.

The code139 rollback package restores the earlier4.0.7 runtime without an
uninstall when signer matches. Future experimental updates must exceed139
if that rollback was installed. See DEVICE-VALIDATION-AR.md.
