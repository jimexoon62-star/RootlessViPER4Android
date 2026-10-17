# 4.0.4 route-recovery test candidate

Fixes the unfiltered AudioDeviceCallback restart loop introduced in 4.0.3 and observed in the October 17 owner trace. Initial inventory and capture submix changes no longer rebuild the pipeline; real selected output changes remain monitored. 56 JVM tests passed. APK testOnly flag absent; signature, native dependencies and alignment verified. Physical HONOR/Spotify acceptance remains pending. Install as an update, without uninstalling or clearing data. See engineering/ROUTE-RECOVERY-CORRECTION.md.
