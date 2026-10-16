# Installation correction — October 16, 2026

The first delivered 4.0.3 candidate (SHA-256 77819a5db86c41b9fb62d1ea2550ac8167cd0b06d8952726496fd555eb67f2b2) was incorrectly described as installable. Its manifest contains android:testOnly=true. The owner reported rejection by the phone's normal installer. This packaging defect was missed by signature/alignment and JVM checks. Those checks did not establish installability. The previous delivery APK has been removed from the delivery branch head.

Corrected artifact: RootlessViPER4Android-4.0.3-install-fix-arm64.apk
Size: 33,616,426 bytes
SHA-256: bc1798e8e11c299a70fc892422ad309cdb381853ab87f0db644a7b6c622098d6
Package/version/certificate remain unchanged. Application audio implementation remains c5ee078; build metadata names evidence commit 6492cc9.

Rebuild performed from source using the supported Gradle toolchain:

```
./gradlew --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false :app:assembleRootlessFdroidRelease
```

Build succeeded. The corrected APK has no android:testOnly attribute. APK signature, ZIP integrity, ARM64 native entries and 16 KiB alignment were verified. tools/verify_installable_apk.py rejects the original delivered APK and accepts the corrected one. This gate must be run against the actual deliverable after packaging. Build application delivery separately with testOnly=false; do not assume successful instrumentation packaging produces a phone-installable APK.

Actual HONOR installation, certificate compatibility with any remaining app data, audio operation and audible recovery remain unverified. The screenshot alone cannot exclude another install failure. The owner reports deleting the previously installed app; settings recovery depends on retained app data or a backup and cannot be guaranteed. No removal, data clearing or device modification was performed by the agent.

Download: https://github.com/jimexoon62-star/RootlessViPER4Android/raw/refs/heads/delivery/rv4a-4.0.3-blackout-test/test-builds/RootlessViPER4Android-4.0.3-install-fix-arm64.apk
