# Executed evidence

## Run 39110841519 — failed probe, no capture conclusion

https://github.com/jimexoon62-star/RootlessViPER4Android/actions/runs/39110841519

Android 16 Google APIs x86_64, actual ADB shell UID 2000:

```
permission CAPTURE_AUDIO_OUTPUT=0
permission CAPTURE_MEDIA_OUTPUT=0
permission MODIFY_AUDIO_ROUTING=0
permission RECORD_AUDIO=0
register=0 routeFlags=3
```

Here 0 means PERMISSION_GRANTED. Registration succeeded. The initial recorder
failed AudioFlinger attribution validation: UID 2000 was paired with package
`android` by the context-free policy helper. The probe also incorrectly stopped
an uninitialized recorder in finally; that masked the initial error. Therefore
neither capture-mode assertion ran and this is not evidence for or against
full-quality capture.

Commit 57e93c2 supplies the actual shell package Context to AudioRecord.Builder;
it does not change UID or permission checks. Cleanup now unregisters policy even
if recorder shutdown throws and avoids stopping an uninitialized recorder.
