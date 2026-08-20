package com.solewis.podcaster.player

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.solewis.podcaster.data.repo.PlayableEpisode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

data class PlaybackUiState(
    val episodeId: String? = null,
    val title: String? = null,
    val podcastTitle: String? = null,
    val isPlaying: Boolean = false
)

/**
 * App-scoped [MediaController] wrapper - the only way the UI touches playback. Held as a single
 * lazily-built instance for the app's lifetime rather than connected/disconnected per screen:
 * that would churn the binder connection and reset state on every rotation. Commands issued
 * before the controller finishes connecting are silently dropped by Media3, which is why every
 * public method here goes through the suspending [controller] rather than a nullable field.
 */
class PlayerConnection(private val context: Context) {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

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
                    podcastTitle = mediaItem?.mediaMetadata?.artist?.toString()
                )
            }
        })
        controller = newController
        return newController
    }

    suspend fun play(episode: PlayableEpisode) {
        val mediaController = controller()
        mediaController.setMediaItem(MediaItemMapper.toMediaItem(episode))
        mediaController.prepare()
        mediaController.play()
    }

    suspend fun togglePlayPause() {
        val mediaController = controller()
        if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
    }
}
