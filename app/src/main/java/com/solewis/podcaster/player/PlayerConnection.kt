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
class PlayerConnection(private val context: Context) : Playback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackUiState())
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
        val mediaController = controller()
        mediaController.setMediaItem(MediaItemMapper.toMediaItem(episode), episode.startPositionMillis)
        mediaController.prepare()
        mediaController.play()
    }

    override suspend fun togglePlayPause() {
        val mediaController = controller()
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }

    override suspend fun seekTo(positionMillis: Long) {
        controller().seekTo(positionMillis)
    }

    override suspend fun skipForward() {
        controller().seekForward()
    }

    override suspend fun skipBack() {
        controller().seekBack()
    }

    override suspend fun setSpeed(speed: Float) {
        controller().setPlaybackSpeed(speed)
    }

    private companion object {
        const val PROGRESS_TICK_MILLIS = 500L
    }
}
