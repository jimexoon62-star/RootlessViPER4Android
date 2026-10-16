# Reproduction and test scope

Supported project toolchain: Gradle wrapper 8.10.2, JDK 17, AGP 8.7.3, Android compile SDK 35, target SDK 34, CMake 3.22.1. This workspace uses NDK 27.0.12077973. Android SDK auto-installs the project library SDK/build tools as needed. Configure `sdk.dir` locally; do not commit machine paths or credentials.

```sh
./gradlew --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false \
  :app:assembleRootlessFdroidRelease \
  :app:testRootlessFdroidReleaseUnitTest \
  :app:assembleRootlessFdroidReleaseAndroidTest
```

The application default ABI list is unchanged. The delivery command selects ARM64. Unit tests mock Android effects, Context, Handler, and session manager explicitly. Android stub default return values permit Service construction; no framework behavior is inferred from those defaults. Production transfer/lease/ownership and service fail-open control flow execute on the JVM.

The release instrumentation APK targets the original application ID, not a second DSP app. It installs no competing capture service. Native tests and the opt-in live fault test are delivered for connected-device validation; compilation/packaging is not execution.

On an authorized connected device with owner-started Spotify/DSP capture and independent audio recording, the opt-in test is:

```sh
adb shell am instrument -w \
  -e class me.timschneeberger.rootlessjamesdsp.service.TargetDeviceRecoveryTest \
  -e allowPlaybackFault yes \
  com.alienware377.viper4android.rootless.test/androidx.test.runner.AndroidJUnitRunner
```

It injects one actual worker IOException, waits for the native mute release diagnostic, and reports elapsed API-observation time. That measurement is not physical audible-restoration time. The source test requires an already running capture service and does not manufacture or bypass capture authorization. The full 60-minute device matrix and a second-failure/exhaustion run remain required.

Before delivery run tools/verify_installable_apk.py against the actual APK. See INSTALLATION-CORRECTION.md: the first delivery was rejected by normal installation because testOnly=true.
