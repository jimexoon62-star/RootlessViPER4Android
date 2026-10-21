# Rootless ViPER4Android4.1.1 — experimental full native engine

**HONOR/Spotify device acceptance is pending. This is not a confirmed Spotify fix.**

[التحقق على الهاتف والرجوع دون مسح البيانات](DEVICE-VALIDATION-AR.md)

The full native backend requires genuine Shizuku in ADB mode. No root is needed.
Package com.alienware377.viper4android.rootless, code140, same test signer as4.1.0.
Use an in-place update only when Android accepts the matching signature. Do not
uninstall or clear application data to work around a signature mismatch.
It replaces the same package rather than creating a competing second pipeline.

The independent guard now bounds stuck policy initialization and cleanup.122 JVM
and30 Android16 emulator tests passed. Read REPORT.md for executed evidence,
three failed tests in the first run, and the corrected measurement method.
The14 native effect tests measure real DSP output, not every possible profile
or effect combination. A client that fails to reopen a disconnected AAudio
stream remains a compatibility boundary; Spotify on HONOR must be tested.

The code141 rollback APK restores the older4.0.7 runtime without a data wipe if
signatures match. It is not a new Spotify repair and has not been physically
installed on the owner's phone. Future updates after141 must use a higher code.

Hashes are in SHA256SUMS. Source branch: fix/full-shell-viper-engine.
Executed Android run: https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39614979983
