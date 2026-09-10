package com.solewis.podcaster.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.data.settings.SettingsStore
import kotlin.math.ceil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * App-scoped [MediaController] wrapper - the only way the UI touches playback. Held as a single
 * lazily-built instance for the app's lifetime rather than connected/disconnected per screen:
 * that would churn the binder connection and reset state on every rotation. Commands issued
 * before the controller finishes connecting are silently dropped by Media3, which is why every
 * public method here goes through the suspending [controller] rather than a nullable field.
 */
class PlayerConnection(
    private val context: Context,
    private val settings: SettingsStore = SettingsStore(context),
    /**
     * Every command issued from here is recorded, so the log can tell an app-initiated jump from
     * one the player made on its own - see [PlaybackLog].
     */
    private val log: PlaybackLog = PlaybackLog.forApp(context)
) : Playback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    /**
     * Set by [restore] and consumed by the first command that needs a real player - see
     * [loadedController].
     */
    private var restored: PlayableEpisode? = null

    // Seeded with the saved speed rather than 1x so Now Playing shows the right number on its
    // first frame, before any controller has connected to confirm it.
    private val _state = MutableStateFlow(PlaybackUiState(speed = settings.speed))
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ProgressUiState())
    override val progress: StateFlow<ProgressUiState> = _progress.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    override val isStalled: StateFlow<Boolean> =
        _state.stalledAfterWaiting().stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch {
            while (true) {
                val mediaController = controller
                if (mediaController == null || !_state.value.isPlaying) {
                    delay(IDLE_POLL_MILLIS)
                    continue
                }
                val position = mediaController.currentPosition
                publishProgress(position)
                delay(millisUntilNextDisplayedSecond(position, _state.value.speed))
            }
        }

        // The other half of [PlaybackErrorRetrier]: that class keeps trying in silence, and this
        // is what notices if it never pays off. Fires once per error - clearing
        // `hasRecoverableNetworkError` here is what stops the spinner along with the message,
        // rather than leaving both the "still loading" and "gave up" stories on screen together.
        scope.launch {
            _state.networkErrorGivenUpAfterWaiting().collect { gaveUp ->
                if (!gaveUp) return@collect
                _errors.tryEmit("Couldn't reconnect - check your connection")
                _state.value = _state.value.copy(hasRecoverableNetworkError = false)
                controller?.pause()
            }
        }
    }

    /**
     * The ticker above only runs while playing, so a seek made *while paused* would otherwise
     * leave the scrubber and progress bars frozen at the pre-seek position until playback
     * resumed. [Player.Listener.onPositionDiscontinuity] covers that, and does it for every
     * source of a seek - the in-app buttons, the notification, Android Auto, a Bluetooth remote -
     * rather than only the few methods on this class.
     */
    private fun publishProgress(positionMillis: Long) {
        _progress.value = ProgressUiState(
            positionMillis = positionMillis,
            durationMillis = controller?.duration?.takeIf { it != C.TIME_UNSET }
        )
    }

    private suspend fun controller(): MediaController {
        controller?.let { return it }

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val newController = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                // The controller does not auto-reconnect once the session is gone (e.g. the
                // service stopped itself after playback ended) - drop the cached instance so the
                // next call through controller() rebuilds rather than issuing commands to a dead
                // connection.
                override fun onDisconnected(controller: MediaController) {
                    this@PlayerConnection.controller = null
                }
            })
            .buildAsync()
            .await()

        newController.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            /**
             * Separate from [onIsPlayingChanged] because they genuinely disagree during a seek,
             * which is the whole reason `playWhenReady` is carried - see [PlaybackUiState].
             */
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _state.value = _state.value.copy(playWhenReady = playWhenReady)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    // Anything other than IDLE - buffering again, ready, ended - means whatever
                    // was wrong has stopped being wrong, whether that was PlaybackErrorRetrier's
                    // doing or the same recovery a real device showed once on its own.
                    hasRecoverableNetworkError = _state.value.hasRecoverableNetworkError &&
                        playbackState == Player.STATE_IDLE
                )
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _state.value = _state.value.copy(
                    episodeId = mediaItem?.mediaId,
                    title = mediaItem?.mediaMetadata?.title?.toString(),
                    podcastTitle = mediaItem?.mediaMetadata?.artist?.toString(),
                    artworkUrl = mediaItem?.mediaMetadata?.artworkUri?.toString(),
                    // Whatever was stuck belonged to the episode being left - carrying it onto a
                    // new one would show a spinner for a problem that no longer has anything to
                    // do with what's now loaded.
                    hasRecoverableNetworkError = false
                )
                // The *new* item's position, not zero. `currentPosition` already refers to the
                // incoming item here - the trap documented on ProgressWriter, useful for once -
                // and that is the resume point the episode is about to start from. Publishing an
                // empty state instead made the bar snap to the beginning and then jump forward
                // again a moment later, on every episode change.
                //
                // Not covered by a test, deliberately rather than by omission. The fault is a
                // transient, and how long it lasts is the gap between this callback and the real
                // position arriving - microscopic against a local file, long enough to see against
                // a buffering network stream. An on-device sampler at 2ms did not catch it even
                // with the old code, so a passing test would have been false assurance. The
                // evidence for the change is the report plus a phone log showing no backwards seek
                // anywhere near those moments, which rules out playback itself moving.
                publishProgress(controller?.currentPosition ?: 0L)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // newPosition, not the controller's currentPosition: on an item transition the
                // latter already refers to the incoming item, which is the same trap documented
                // on ProgressWriter for recording progress against the wrong episode.
                publishProgress(newPosition.positionMs)
            }

            /**
             * The buffer running out with no network to refill it arrives here, which is what an
             * episode stopping a minute after the signal went actually is.
             *
             * A recoverable network error is not reported immediately any more - [PlaybackErrorRetrier]
             * is about to try to recover it, and a message plus a spinner both appearing for the same
             * problem, one of them permanent-looking and one not, is worse than either alone. The
             * spinner comes from [hasRecoverableNetworkError] below; the message is deferred to
             * [networkErrorGivenUpAfterWaiting], and fires only if retrying genuinely runs out. A
             * broken or missing episode is not retryable at all, so that one is still reported at once.
             */
            override fun onPlayerError(error: PlaybackException) {
                if (error.isRecoverableNetworkError()) {
                    _state.value = _state.value.copy(hasRecoverableNetworkError = true)
                } else {
                    _errors.tryEmit("Playback stopped - couldn't load this episode")
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _state.value = _state.value.copy(speed = playbackParameters.speed)
            }
        })
        controller = newController
        // The listener above only ever hears about *changes*. Connecting to a session that is
        // already playing - started from the notification, the car, or a headset button - fires
        // nothing at all, so without this the UI would sit on its default paused state while
        // audio came out of the speaker. Read the truth once, up front.
        adoptSessionState(newController)
        return newController
    }

    /**
     * Replaces the UI's playback state with whatever the session actually holds.
     *
     * Returns false when the session has no episode loaded, which is the caller's cue that there
     * is nothing to adopt and the saved position in Room is still the best thing to show.
     */
    private fun adoptSessionState(mediaController: MediaController): Boolean {
        val item = mediaController.currentMediaItem ?: return false
        log.record(
            "ADOPT",
            "item=${item.mediaId} pos=${mediaController.currentPosition} " +
                "playing=${mediaController.isPlaying}"
        )
        // The session is the authority now, so a pending restore must not be applied over it -
        // loadedController would otherwise reload this same episode at its Room position and
        // undo a seek made from the notification.
        restored = null
        _state.value = PlaybackUiState(
            episodeId = item.mediaId,
            title = item.mediaMetadata.title?.toString(),
            podcastTitle = item.mediaMetadata.artist?.toString(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            isPlaying = mediaController.isPlaying,
            playWhenReady = mediaController.playWhenReady,
            isBuffering = mediaController.playbackState == Player.STATE_BUFFERING,
            // Read honestly rather than defaulted to false: opening the app while
            // PlaybackErrorRetrier is already mid-retry, on a session that started or dropped its
            // connection without the app around to see it happen, should show the same spinner it
            // would have shown if the app had been open the whole time.
            hasRecoverableNetworkError = mediaController.playbackState == Player.STATE_IDLE &&
                mediaController.playerError?.isRecoverableNetworkError() == true,
            speed = mediaController.playbackParameters.speed
        )
        publishProgress(mediaController.currentPosition)
        return true
    }

    /**
     * Picks up playback that was started or changed outside the app, without starting a service
     * that isn't already there.
     *
     * The reported bug: play from the pull-down notification while the app is closed, then tap the
     * notification to open it, and the app showed the episode paused while it was audibly playing.
     * The controller is built lazily, on the first command the user issues - so on a launch where
     * they issue none, nothing ever connected, and nothing ever contradicted the paused state
     * [restore] had put up from Room.
     */
    override suspend fun syncWithSession(): Boolean {
        // Nothing running means nothing to adopt, and asking by connecting would start the very
        // service whose absence is the answer - see PlaybackService.isRunning.
        if (controller == null && !PlaybackService.isRunning) return false
        return adoptSessionState(controller())
    }

    override suspend fun play(episode: PlayableEpisode) {
        log.record("CMD_PLAY", "item=${episode.episodeId} startPos=${episode.startPositionMillis}")
        restored = null
        val mediaController = controller()
        mediaController.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
        mediaController.prepare()
        mediaController.play()
    }

    /**
     * Re-seeds the UI only. Killing the app takes the playback service and its `ExoPlayer` with
     * it, and the player's own playlist is the sole source of [PlaybackUiState] - so without this
     * the app comes back with no player at all, even though the position was in Room the whole
     * time.
     *
     * Nothing is loaded into a player here, deliberately. Doing that would mean binding the
     * playback service and buffering audio on every cold start, including the many launches where
     * the user only wants to browse; [loadedController] defers both to the first command that
     * genuinely needs a player.
     *
     * Overwrites whatever [state] holds, so it must not be called over a live session - see
     * [PlaybackRestorer], which owns that decision.
     */
    override suspend fun restore(episode: PlayableEpisode) {
        log.record("RESTORE", "item=${episode.episodeId} pos=${episode.startPositionMillis}")
        restored = episode
        _state.value = _state.value.copy(
            episodeId = episode.episodeId,
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            artworkUrl = episode.artworkUrl,
            isPlaying = false,
            playWhenReady = false
        )
        _progress.value = ProgressUiState(
            positionMillis = episode.startPositionMillis,
            durationMillis = episode.durationMillis
        )
    }

    /**
     * The controller, with a [restored] episode loaded into it if one is still pending. Every
     * transport command goes through this rather than [controller] so the first tap on a restored
     * player does the loading that [restore] skipped, instead of being issued to an empty player
     * and silently doing nothing.
     */
    private suspend fun loadedController(): MediaController {
        val mediaController = controller()
        val episode = restored
        if (episode != null) {
            restored = null
            // Guarded because the session may have acquired an item by another route since the
            // restore - Android Auto, or a media button resuming playback while the app sat idle.
            if (mediaController.currentMediaItem == null) {
                // A prime suspect for the reported jump-back: `restored` is captured when the app
                // starts, so if this fires *after* playback has been running the position it
                // loads is stale by however long that is.
                log.record(
                    "LOAD_RESTORED",
                    "item=${episode.episodeId} pos=${episode.startPositionMillis}"
                )
                mediaController.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
                mediaController.prepare()
                return mediaController
            }
        }
        // A lingering fatal error - PlaybackErrorRetrier gave up, or one arrived before anything
        // was listening for it - would otherwise make every command below a silent no-op:
        // `play()`, `seekTo()` and the skip commands do not clear or retry a player error on their
        // own, only `prepare()` does. Confirmed by reproducing it: skipping forward and back on a
        // stuck player accepted the taps and changed nothing. Whatever the user just asked for
        // deserves a real attempt rather than silence.
        if (mediaController.playerError != null) {
            log.record("CLEAR_ERROR_AND_RETRY", "pos=${mediaController.currentPosition}")
            mediaController.prepare()
        }
        return mediaController
    }

    override suspend fun togglePlayPause() {
        val mediaController = loadedController()
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }

    override suspend fun pause() {
        // Not loadedController(): pausing something that was never loaded has nothing to pause, and
        // loading it just to stop it would start a service and a buffer for no reason.
        controller?.pause()
    }

    override suspend fun seekTo(positionMillis: Long) {
        log.record("CMD_SEEK", "to=$positionMillis")
        loadedController().seekTo(positionMillis)
    }

    override suspend fun skipForward() {
        log.record("CMD_SKIP_FORWARD")
        loadedController().seekForward()
    }

    override suspend fun skipBack() {
        log.record("CMD_SKIP_BACK")
        loadedController().seekBack()
    }

    override suspend fun setSpeed(speed: Float) {
        controller().setPlaybackSpeed(speed)
    }

    /**
     * Drops the controller and stops the progress ticker.
     *
     * Nothing in the app calls this - the connection is app-scoped and outlives every screen on
     * purpose. It exists so a test can stand up more than one of these in a process without the
     * discarded ones keeping a binder connection and a coroutine alive to interfere with the next.
     */
    @androidx.annotation.VisibleForTesting
    fun release() {
        controller?.release()
        controller = null
        scope.cancel()
    }

    private companion object {
        /** How often to look for playback having started, while nothing is playing. */
        const val IDLE_POLL_MILLIS = 500L
    }
}

/**
 * How long to wait before the on-screen clock should change, given where playback is and how fast
 * it is going.
 *
 * The ticker used to sample every 500ms of *wall* time and then floor the result to whole seconds,
 * which is fine only when those two rates line up. At 1.75x each sample advances 875ms of media,
 * and since 875 does not divide 1000, one displayed second in every seven gets two samples instead
 * of one - so that second sits on screen for twice as long as its neighbours. Roughly one visible
 * hitch every four seconds, and the reason it was never noticed at 1x or 2x, where the rates divide
 * evenly and every second gets the same number of samples.
 *
 * Waiting for the *next second boundary in media time* removes the aliasing rather than reducing
 * it: the display advances exactly once per displayed second at any speed. It is also fewer
 * wakeups than before at normal speed.
 */
internal fun millisUntilNextDisplayedSecond(positionMillis: Long, speed: Float): Long {
    val untilNextSecond = 1_000L - (positionMillis % 1_000L)
    val wallMillis = untilNextSecond / speed.coerceAtLeast(MIN_SPEED)
    // Rounded *up*, so the wait lands on or a hair past the boundary. Flooring undershoots it by
    // a fraction of a millisecond every single time, which costs a second wakeup to cover the
    // remainder - and that wakeup lands inside the next second, making the displayed seconds
    // uneven again in exactly the way this exists to prevent. Overshooting by under a millisecond
    // of media is free, since the display floors to whole seconds anyway.
    return ceil(wallMillis).toLong().coerceIn(MIN_TICK_MILLIS, MAX_TICK_MILLIS)
}

/** Guards against a nonsensical or zero speed turning the delay into an infinity. */
private const val MIN_SPEED = 0.1f

/** Never busier than 20 wakeups a second, however close to a boundary a seek happens to land. */
private const val MIN_TICK_MILLIS = 50L

/** At the slowest speed a second of media still takes at most this long to arrive. */
private const val MAX_TICK_MILLIS = 1_000L

/**
 * How long playback has to be waiting on data before the UI says so.
 *
 * A seek rebuffers in 100-250ms in the good case, measured on device - so this is not trying to be
 * longer than any seek, which is unachievable anyway: the same seek was seen taking over 500ms on a
 * loaded emulator. It sits where a wait stops being invisible and starts reading as a hang, the
 * usual one-second mark. Below it the control holds still; above it a seek genuinely *is* a wait
 * worth showing, and a spinner is the honest answer rather than a flicker.
 */
internal const val STALL_VISIBLE_AFTER_MILLIS = 1_000L

/**
 * Turns "waiting on data" into "waiting long enough to say so".
 *
 * Extracted as a plain flow operator, the way [millisUntilNextDisplayedSecond] is, because the rule
 * is a timing rule and nothing else - and testing it against a real player turned out to measure
 * how fast the emulator felt that minute rather than whether the rule holds.
 *
 * Waiting means buffering *and* meant to be playing. Not merely "not making sound": playback is
 * also silent while suppressed - during a phone call, say - and a spinner there would be a lie.
 */
internal fun Flow<PlaybackUiState>.stalledAfterWaiting(
    afterMillis: Long = STALL_VISIBLE_AFTER_MILLIS
): Flow<Boolean> = map { (it.isBuffering || it.hasRecoverableNetworkError) && it.playWhenReady }
    .distinctUntilChanged()
    // transformLatest, so a wait that ends before the delay elapses cancels its own pending
    // emission rather than announcing itself after the fact.
    .transformLatest { waiting ->
        if (!waiting) {
            emit(false)
        } else {
            delay(afterMillis)
            emit(true)
        }
    }
    .distinctUntilChanged()

/**
 * True once a recoverable network error has been persisting - not recovered, not paused, no
 * different episode taking over - for at least [afterMillis]. The same shape as
 * [stalledAfterWaiting] at a much longer delay: a spinner is a fair thing to show for a few
 * seconds, and stops being one once the wait has gone on long enough that it needs an explicit
 * "this did not work" instead.
 */
internal fun Flow<PlaybackUiState>.networkErrorGivenUpAfterWaiting(
    afterMillis: Long = ReconnectPolicy().giveUpAfterMillis
): Flow<Boolean> = map { it.hasRecoverableNetworkError && it.playWhenReady }
    .distinctUntilChanged()
    .transformLatest { stillWaiting ->
        if (!stillWaiting) {
            emit(false)
        } else {
            delay(afterMillis)
            emit(true)
        }
    }
    .distinctUntilChanged()
