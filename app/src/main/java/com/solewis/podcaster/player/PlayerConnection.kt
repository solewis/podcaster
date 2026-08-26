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
import com.solewis.podcaster.data.settings.PlaybackSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val settings: PlaybackSettings = PlaybackSettings(context)
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

    init {
        scope.launch {
            while (true) {
                delay(PROGRESS_TICK_MILLIS)
                val mediaController = controller
                if (mediaController != null && _state.value.isPlaying) {
                    publishProgress(mediaController.currentPosition)
                }
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

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                _state.value = _state.value.copy(speed = playbackParameters.speed)
            }
        })
        controller = newController
        return newController
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

    private companion object {
        const val PROGRESS_TICK_MILLIS = 500L
    }
}
