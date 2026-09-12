# Working on Podcaster

## Devices — read this before running any on-device test

`5C181JEA305125` (Pixel 10a) is the owner's real phone, with their real podcast library on it.
`emulator-5554` is the test device. **Never run tests, installs, or any `adb` command against the
phone unless the owner explicitly asks for that run.**

`./gradlew :app:connectedDebugAndroidTest` targets **every connected device**, so it silently
includes the phone the moment it is plugged in. It is not a safe command to type on its own. Always
pin the device:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug
```

Check `adb devices` first if there is any doubt about what is attached.

This has gone wrong once: a repeat-run loop was started while only the emulator was connected, the
phone was plugged in partway through, and the next iteration installed and ran the whole
instrumentation suite on it. Nothing was lost - `PodcasterTestRunner` substitutes `TestPodcasterApp`,
which builds its container on an in-memory database, so no test can read or write the real library -
but for the length of the run the app on screen was backed by that empty database and looked wiped,
which is alarming and entirely avoidable.

Never run destructive `adb` commands (`uninstall`, `pm clear`, `rm`) against the phone at all.

## Tests

- `./gradlew build test` - the JVM suite, safe to run any time.
- On-device tests need the emulator running and the `ANDROID_SERIAL` pin above.
- Prove a fix is load-bearing: revert the change and confirm the new test fails, then restore it.
  The player/UI seam in particular has shipped bugs that passed a full green suite.

## Branches

`main` is PR-protected - direct pushes are rejected. Push the branch and hand over the PR URL.
