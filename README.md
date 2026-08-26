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

Built on Media3's `DownloadManager`/`DownloadService`. There are deliberately **two** caches: a
`NoOpCacheEvictor` download cache in `filesDir`, and the LRU-evicted streaming cache in `cacheDir`.
Sharing one would mean an episode you downloaded for a flight getting evicted to make room for
something streamed. `PlayerFactory` nests them so the download cache is read first and never
written — see its doc for why read-only matters.

Both caches and the download manager live in `MediaStorage` at **process** scope, not in
`AppContainer`. Media3 allows one `SimpleCache` per directory per process, while `AppContainer` is
deliberately built more than once (an instrumentation test installs its own). That was harmless
until a list row started observing downloads, at which point both containers opened the download
cache and the second threw. Only the on-device smoke tests catch this, since they are the one place
two containers coexist.

- [x] Download an episode, and delete or cancel one
- [x] Per-episode state: queued, downloading, downloaded, failed, removing
- [x] Downloads screen (Activity → Downloads) with a running total
- [x] `FOREGROUND_SERVICE_DATA_SYNC`, and `WorkManagerScheduler` so an interrupted download resumes
- [ ] Storage cap, and auto-delete once played — nothing evicts the download cache by design, so
      its size is currently governed only by what you delete by hand
- [ ] Wifi-only. Manual downloads deliberately use `Requirements.NETWORK`: tapping download is a
      request for the episode *now*, and silently waiting for wifi looks identical to being broken.
      `NETWORK_UNMETERED` belongs to auto-download, which nobody asked for episode-by-episode.
- [x] A download control on the **Home feed** rows — unblocked by the row overflow menu below.
- [ ] Delete orphaned downloads. Unsubscribing leaves rows in Media3's index with no episode to
      label them; `DownloadsViewModel` hides them, but the files stay until deleted by hand.
- [ ] `POST_NOTIFICATIONS`. The download progress notification is *not* exempt the way the media
      notification is, so on Android 13+ with the permission denied, downloads run silently.

### 2. Settings

Reached from the gear on the Library header rather than a fourth tab — somewhere you go once and
then forget, which is the opposite of a tab. `SettingsStore` is the single writer; everything else
observes it, so a change lands without restarting anything.

- [x] Skip amounts: **5s / 15s / 30s**, forward and back set independently
- [x] Theme: system / light / dark
- [x] Auto-advance toggle
- [ ] Per-show speed override — `PodcastEntity.playbackSpeedOverride` already exists, unused
- [ ] Auto-download: off / new episodes / per-show
- [ ] "Mark played at" threshold (`CompletionRule` decides this silently)
- [ ] Skip intro/outro seconds, per show
- [ ] New-episode notifications — needs `POST_NOTIFICATIONS`, unlike the media notification, which
      is exempt

Two notes on the skip amounts, since both were assumptions that turned out to be wrong:

- **No custom icons were needed.** `SkipIcon` already takes the seconds and draws the numeral, and
  Media3 ships `ICON_SKIP_BACK_5`/`_15`/`_30` for the notification and the car. Those baked icons
  are the reason the setting offers three fixed values instead of a slider: every settable amount
  has a glyph that states it truthfully.
- **The amount cannot live on the `ExoPlayer`.** Its seek increments are fixed when it is built, so
  a configurable amount would mean rebuilding the player mid-listen. `TimedSkipPlayer` owns them
  instead and reads them per press. The trap there is worth knowing:
  `ForwardingSimpleBasePlayer.handleSeek` implements `COMMAND_SEEK_BACK` as `seekBack()` on the
  *wrapped* player, ignoring the increment the wrapper reports — so overriding only what is reported
  gives a button that says 30 and moves 15. `TimedSkipPlayerTest` pins both halves.

### 3. Missing table stakes

- [x] **Mark played / unplayed by hand** (episode screen), and **mark all as played** for a show
      (the show's overflow menu, which reports how many it changed).

      Two things are load-bearing here. Marking *one* episode played stamps `lastPlayedAt`, so the
      jump pill moves on and offers the next episode — the point of the action. Marking a *whole
      show* played deliberately does not, because stamping the same timestamp on hundreds of rows
      would leave `JumpTargetResolver` picking between ties and make the pill's target depend on row
      order. And when the episode is the one loaded in the player, the mark alone is not enough:
      `ProgressWriter` re-derives `isPlayed` from the live player position every five seconds and
      nothing stored can stop it, so marking it also seeks to the end.
- [x] **Row trailing actions collapsed into an overflow menu** (`EpisodeActionsMenu`), shared by
      the Home feed and a show's episode list. Play stays a direct button; queue, download and
      mark-played moved into `⋮`. Two 48dp targets instead of three or four, and the title got most
      of that width back.

      Download *progress* moved to the row's metadata line ("25m · Downloading 42%") rather than
      into the menu — a menu you have to open is no place for a progress indicator. The trade is
      that downloading is two taps instead of one, which is what Pocket Casts and Overcast do too.
- [ ] Search and filter within a show, including filter-to-unplayed. A single show has been tested
      at 2952 episodes; scrolling that to find one is rough.
- [ ] Share an episode
- [x] **Sleep timer** on Now Playing: 5/15/30/45/60 minutes, "end of episode", +5 minutes, and off.
      App-scoped (`AppContainer`) rather than owned by a ViewModel, so it keeps counting once the
      screen is gone — verified on device: 4:56 → 3:24 across leaving and re-entering Now Playing,
      matching playback's own elapsed time exactly.

      "End of episode" is not implemented by watching for the episode to end. An episode reaching
      its end already leaves the player stopped, so the only thing that would carry on is
      `AutoAdvancer` starting the next one — the timer just declines that once
      (`consumeEndOfEpisode`). That avoids two listeners racing over the same `STATE_ENDED`, where
      the outcome would depend on registration order.
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
