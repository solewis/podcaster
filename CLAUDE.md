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

## Crash reporting

Firebase Crashlytics, project `podcaster-b3b10`. `app/google-services.json` is committed
deliberately - the `api_key` in it is scoped to the package name and signing certificate, so it
identifies the app rather than authorising anything, and the `google-services` plugin fails the
build outright when the file is absent. The signing keystore is the secret, and `.gitignore`
already covers it.

No initialisation code: `firebase-common` contributes a ContentProvider that starts Crashlytics
before `Application.onCreate`, early enough to catch a crash in `onCreate` itself.

To prove the pipeline still works end to end (nothing in the app triggers a crash on purpose):

```sh
adb shell setprop log.tag.FirebaseCrashlytics DEBUG   # upload logs are DEBUG-level, off by default
# add `throw RuntimeException("test")` to MainActivity.onCreate, then:
ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug
adb shell am start -n com.solewis.podcaster/.MainActivity   # expect "Handling uncaught exception"
# revert the throw, reinstall, and launch again - the report uploads on the *next* start
adb shell run-as com.solewis.podcaster ls files/.crashlytics.v3/com.solewis.podcaster/priority-reports
```

An empty `priority-reports` after that last launch is the confirmation: a fatal is queued there at
crash time and only deleted once it has been sent, so a failed upload leaves the file behind.

`adb shell am crash` is not a substitute - it throws `RemoteServiceException$CrashedByAdbException`
from inside `ActivityThread`, which never reaches the uncaught-exception handler, so Crashlytics
records nothing and the check silently proves the opposite of what it looks like.
