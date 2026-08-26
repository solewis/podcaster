# Podcaster

A podcast player for Android. Native Kotlin/Compose, Media3 for playback, Room for storage.

Built around one idea: reopening the app should put you exactly where you left off, in as few taps
as possible. Hence the resume pill on a show, and hence Android Auto being a first-class target
rather than an afterthought — most listening happens while driving.

No backend, no accounts, no telemetry. Everything lives on the device.

## Working on it

```bash
./gradlew build test                # lint + the JVM suite (repositories, ViewModels, screens)
ANDROID_SERIAL=emulator-5554 \
  ./gradlew :app:connectedDebugAndroidTest   # the on-device tier
```

> **Always pin `ANDROID_SERIAL` for instrumented tests.** `connectedDebugAndroidTest` fans out to
> every attached device, and AGP uninstalls the app afterwards — which wipes that device's
> database, subscriptions and listening positions. With a phone plugged in, the phone loses its
> real data. Confirmed by `dumpsys package`: after a run, `firstInstallTime == lastUpdateTime`,
> i.e. a fresh install rather than an update.

CI runs both tiers in parallel on every PR (`.github/workflows/ci.yml`).

## Road to the Play Store

The plan is a real release, so people other than the author can use it and send feedback. Android
Auto makes Play distribution load-bearing rather than optional: sideloaded builds only work in Auto
with "Unknown sources" turned on in Auto's developer settings, so shipping to anyone else means
Play plus Auto-category review.

### Done

- [x] Subscribe, browse, play, resume — including the resume pill
- [x] Personal "Up Next" queue with auto-advance
- [x] Android Auto browse tree, resuming at the saved position
- [x] Steering-wheel `<<`/`>>` mapped to timed skips
- [x] Playback position and chosen speed survive the app being killed
- [x] Refresh on foreground and on opening a show, plus a periodic worker
- [x] Test suite across three tiers, and CI

### 1. Downloads

Unusually well set up already: `SimpleCache` is a process-wide singleton and `MediaItemMapper`
sets `customCacheKey` to the episode id specifically so downloads and the streaming cache can
share one store. Media3's `DownloadManager`/`DownloadService` plugs into exactly that.

- [ ] Download an episode, and delete a download
- [ ] Per-episode state: queued, downloading, downloaded, failed
- [ ] A Downloads screen, with total size
- [ ] Storage cap, and auto-delete once played
- [ ] Wifi-only constraint
- [ ] `FOREGROUND_SERVICE_DATA_SYNC` — note the daily runtime budget on Android 14+, and that it
      needs its own Play Console justification

### 2. Settings

There is no Settings screen at all today.

- [ ] Skip amounts: **5s / 15s / 30s**, forward and back set independently. Three preset values
      rather than a free number so each can have a drawn icon — the Auto buttons currently use
      Media3's built-in `ICON_SKIP_BACK_15`/`ICON_SKIP_FORWARD_15`, which would silently lie once
      the amount is configurable, so this needs six icons of our own (three per direction).
      15s is hardcoded in `PlayerFactory` today.
- [ ] Per-show speed override — `PodcastEntity.playbackSpeedOverride` already exists, unused
- [ ] Auto-download: off / new episodes / per-show
- [ ] Auto-advance toggle (`AutoAdvancer` runs unconditionally today)
- [ ] "Mark played at" threshold (`CompletionRule` decides this silently)
- [ ] Skip intro/outro seconds, per show
- [ ] Theme: system / light / dark
- [ ] New-episode notifications — needs `POST_NOTIFICATIONS`, unlike the media notification, which
      is exempt

### 3. Missing table stakes

- [ ] **Mark played / unplayed by hand**, and mark-all-played for a show. No such action exists
      anywhere right now — played state is only ever inferred from listening, which makes a fresh
      subscription to a long-running show impossible to dig out of.
- [ ] Search and filter within a show, including filter-to-unplayed. A single show has been tested
      at 2952 episodes; scrolling that to find one is rough.
- [ ] Share an episode
- [ ] Sleep timer, with an "end of current episode" option. Has to keep running while the app is
      backgrounded, so it belongs alongside the session in `PlaybackService`, not in a ViewModel.
- [ ] Offline and error states across every screen

### 4. Import and export

No accounts, and none needed. Two separate problems:

- [ ] **OPML import** — the universal podcast subscription format, which every other app exports.
      This is how someone arriving from Overcast or Pocket Casts brings their shows in.
- [ ] **OPML export**
- [ ] **JSON backup and restore** to a user-chosen file. OPML carries subscriptions only — no
      positions, no queue, no played flags — so device migration needs its own format.
- [ ] Verify Android Auto Backup actually carries the database. `allowBackup="true"` is set and
      both rule files are still untouched Studio templates with everything commented out, which
      should mean default behaviour, i.e. it already works. Worth proving rather than assuming, and
      watch the ~25MB per-app quota against a library with thousand-episode shows.

The accepted cost of no accounts is **no sync between two devices**. Phone-and-car is one device,
so this is a real limitation that probably never bites.

### 5. Release readiness

- [ ] App icon — still the default Studio adaptive icon
- [ ] A real name; `app_name` is currently the literal string "Podcaster"
- [ ] Release signing config, and Play App Signing. There is no signing config at all today.
- [ ] Turn on R8. `isMinifyEnabled = false` for release right now — and enabling it invalidates the
      reason release unit tests are currently disabled in `app/build.gradle.kts` ("identical code,
      minify off"), so re-enable them at the same time.
- [ ] `versionCode`/`versionName` strategy — still 1 / 0.1
- [ ] Accessibility pass: content descriptions, TalkBack, large fonts, touch targets
- [ ] Privacy policy URL — required by Play even when collecting nothing
- [ ] Data Safety form
- [ ] Foreground service justifications for `mediaPlayback` and `dataSync`
- [ ] Content rating, store listing, screenshots
- [ ] Android Auto category review

No accounts means no account-deletion requirement, which removes one whole Play policy surface.
