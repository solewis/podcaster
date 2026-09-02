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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val settings: SettingsStore = SettingsStore(context)
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _state.value = _state.value.copy(
                    episodeId = mediaItem?.mediaId,
                    title = mediaItem?.mediaMetadata?.title?.toString(),
                    podcastTitle = mediaItem?.mediaMetadata?.artist?.toString(),
                    artworkUrl = mediaItem?.mediaMetadata?.artworkUri?.toString()
                )
                _progress.value = ProgressUiState()
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
             * episode stopping a minute after the signal went actually is. Reported rather than
             * swallowed: silence with no explanation is indistinguishable from a crash.
             */
            override fun onPlayerError(error: PlaybackException) {
                _errors.tryEmit(
                    if (error.errorCode in NETWORK_ERROR_CODES) {
                        "Playback stopped - no connection"
                    } else {
                        "Playback stopped - couldn't load this episode"
                    }
                )
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
        restored = episode
        _state.value = _state.value.copy(
            episodeId = episode.episodeId,
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            artworkUrl = episode.artworkUrl,
            isPlaying = false
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
        val episode = restored ?: return mediaController
        restored = null
        // Guarded because the session may have acquired an item by another route since the
        // restore - Android Auto, or a media button resuming playback while the app sat idle.
        if (mediaController.currentMediaItem == null) {
            mediaController.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
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
        loadedController().seekTo(positionMillis)
    }

    override suspend fun skipForward() {
        loadedController().seekForward()
    }

    override suspend fun skipBack() {
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

        /** Worth telling apart, because "no connection" is actionable and "broken feed" is not. */
        val NETWORK_ERROR_CODES = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
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
