package com.solewis.podcaster.player

import com.solewis.podcaster.data.repo.PlayableEpisode
import kotlinx.coroutines.flow.StateFlow

data class PlaybackUiState(
    val episodeId: String? = null,
    val title: String? = null,
    val podcastTitle: String? = null,
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val speed: Float = 1f
)

/** Deliberately separate from [PlaybackUiState]: position ticks every 500ms while playing, and
 * nothing should recompose off that except an actual scrubber/progress indicator. */
data class ProgressUiState(
    val positionMillis: Long = 0,
    val durationMillis: Long? = null
)

/**
 * Everything the UI is allowed to do to playback. [PlayerConnection] is the real implementation,
 * talking to the playback service over a `MediaController`.
 *
 * The interface exists so a screen's logic can be tested without starting real playback. That is
 * not a hypothetical convenience: the two playback-adjacent bugs found by hand in this app - a
 * spinner left stuck on a row after pausing, and a progress bar frozen after a seek while paused -
 * were both pure state handling on this side of the boundary, reachable in a few lines of test with
 * a fake and not reachable at all without one.
 */
interface Playback {

    val state: StateFlow<PlaybackUiState>

    /** Ticks while playing; also updated on any seek, from any source. */
    val progress: StateFlow<ProgressUiState>

    suspend fun play(episode: PlayableEpisode)

    /**
     * Puts [episode] back in front of the user after the app was killed - shown, paused, at its
     * saved position - without starting playback. See [PlayerConnection.restore] for what is and
     * is not actually loaded into a player.
     */
    suspend fun restore(episode: PlayableEpisode)

    suspend fun togglePlayPause()

    /**
     * Unconditional, unlike [togglePlayPause] - for callers that mean "stop" rather than "the user
     * pressed the button", such as [SleepTimer]. Toggling would resume playback that had already
     * been paused by hand.
     */
    suspend fun pause()

    suspend fun seekTo(positionMillis: Long)

    suspend fun skipForward()

    suspend fun skipBack()

    suspend fun setSpeed(speed: Float)
}
