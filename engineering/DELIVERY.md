# Delivery — 4.0.3-blackout-test (130)

Status: signed ARM64 engineering candidate built and verified; HONOR/Spotify audible acceptance pending. The initiating cause of the supplied blackout remains unproven because the dump contains no application log.

Source implementation commit: `c5ee078f61eb3029592649d8c028842e0111def1` (embedded as `c5ee078` in the actual delivered DEX).
Branch: https://github.com/jimexoon62-star/RootlessViPER4Android/tree/fix/rootless-audio-blackout-honor-android16
Baseline: installed `d69429b`, preserved as ancestor. The follow-up evidence commit adds release-failure tests and documentation only; application runtime code is unchanged.

## APK identity

- File: RootlessViPER4Android-4.0.3-blackout-test-arm64.apk
- Size: 33,616,450 bytes
- SHA-256: `77819a5db86c41b9fb62d1ea2550ac8167cd0b06d8952726496fd555eb67f2b2`
- Package: `com.alienware377.viper4android.rootless`
- Version: 4.0.3-blackout-test, code 130
- ABI: arm64-v8a; four required DSP/logging/import JNI libraries retained.
- Signing identity: repository Android debug/test RSA certificate, `CN=Android Debug, O=Android, C=US`.
- Certificate SHA-256: `dfc8c77e9f4ec2b8ff5d133320df6f51eb2440fa32cb07f5d1a910199e3f9675`.
- This is not a claim of access to a maintainer's private signing key. Installed-app certificate comparison is outstanding. A mismatch safely blocks the update; do not uninstall or clear data.

The separate 1,100,401-byte instrumentation APK targets this same application, contains no capture service, and is not the app to open for playback. SHA-256: `da9bc15a5c4046f07c2613c9cba48c9384254f22429d36353bee37e7454404a0`.

## Executed verification

Final Gradle command (exit 0; BUILD SUCCESSFUL, 1m 6s):

```sh
./gradlew --no-daemon --max-workers=2 -Pandroid.injected.build.abi=arm64-v8a \
  :app:assembleRootlessFdroidRelease \
  :app:testRootlessFdroidReleaseUnitTest \
  :app:assembleRootlessFdroidReleaseAndroidTest
```

The environment wrapper supplies proxy/CA trust and JDK/SDK paths; it does not change application source. Final raw build log and JUnit XML accompany the local delivery.

- JVM tests: **54 executed, 0 failures, 0 errors, 0 skipped**. Includes 10 unchanged baseline tests and 44 recovery/diagnostic tests.
- Production transfer tests: normal, temporary/permanent zero, negative read/write, partial transfer offsets, alignment, oversize and cancellation.
- Production ownership/release tests: startup refusal, removal, duplicate discovery, repeated enable/disable, queued stale callbacks, concurrent shutdown, attachment failure, disable failure, bounded native-release retry, explicit unresolved-release diagnostic, and continued release of other effects.
- Actual service control flow with Android adapters mocked: immediate release before queued teardown, concurrent fatal failures, session loss, idle-aware watchdog, projection revocation, Bluetooth removal and coalesced policy-recreation requests.
- Signal/output health: silent capture expires mute authorization without failure; non-finite or persistent dead DSP output fails; initial route assignment does not loop; selection between connected routes is detected.
- Actual delivered APK: apksigner verification passed; matching instrumentation certificate; ARM64-only manifest/native entries; dependency and mandatory DSP exports verified from extracted libraries; HAL `AELI` absent; ELF LOAD and stored APK native entries pass 16 KiB alignment checks.
- Device access: `adb devices -l` returned an empty device list. **No current emulator or physical-device instrumentation test was executed.** Older branch emulator results are not counted.

Earlier attempts are retained as failures, not passes: direct Java download could not connect without proxy settings; downloaded JDK needed the environment CA store; an erroneous synchronization annotation on an interface failed compilation and was corrected; the first service-test run had five setup failures from unconfigured Android Looper stubs. Subsequent corrected runs passed 49, then 51, then the final 54 tests. A preliminary multi-ABI debug build was intentionally interrupted in favor of the requested ARM64 FOSS release.

## Remaining acceptance and risk

No 60-minute HONOR playback, Bluetooth/speaker recovery, independently observed audible restoration, current platform policy leak test or end-to-end recovery time has passed in this workspace. Hardware acceptance is explicitly pending, with the opt-in fault-injection test packaged for later execution.

The implementation restores source suppression ownership before retrying a defective pipeline; it does not prove physical audibility. If the Android effect release API fails twice, diagnostics explicitly mark release unconfirmed. A permanently blocked native worker is not freed or replaced unsafely; that engine can remain allocated until the worker exits or the process ends. The thresholds and nonblocking scheduling strategy need physical power/latency validation. Do not classify the candidate as a proven fix or full device acceptance.

See ENGINEERING.md for chronology, changed behavior and rationale; DEVICE-ACCEPTANCE.md for installation, independent audio measurements and data-preserving rollback. All diagnostic files remain private unless the owner chooses to share them.
