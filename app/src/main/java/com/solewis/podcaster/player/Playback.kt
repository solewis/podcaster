package com.solewis.podcaster.player

import com.solewis.podcaster.data.repo.PlayableEpisode
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class PlaybackUiState(
    val episodeId: String? = null,
    val title: String? = null,
    val podcastTitle: String? = null,
    val artworkUrl: String? = null,
    /** Actually making sound right now. What a spinner and the progress ticker care about. */
    val isPlaying: Boolean = false,
    /**
     * Playing *or* about to be, once buffering finishes - Media3's `playWhenReady`, and what every
     * play/pause button should draw.
     *
     * The distinction is not academic. A seek drops a playing ExoPlayer into `STATE_BUFFERING`, so
     * `isPlaying` goes false and comes back true a moment later while `playWhenReady` stays true
     * throughout. Buttons bound to `isPlaying` therefore flicked from pause to play and back on
     * every scrub, which read as playback having stopped and restarted itself.
     */
    val playWhenReady: Boolean = false,
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

    /**
     * Playback failing after it had started - most often a buffer running dry with no network left
     * to refill it. Nothing surfaced these before, so an episode that stopped mid-sentence looked
     * like the app had simply given up without saying anything.
     */
    val errors: SharedFlow<String>

    suspend fun play(episode: PlayableEpisode)

    /**
     * Adopts the playback service's own state, if a session is already alive, and reports whether
     * there was one to adopt.
     *
     * Exists because playback can start without the app: the pull-down notification, Android Auto,
     * a headset button. Nothing in here should start a service that is not already running - a
     * launch where the user only wants to browse must stay as cheap as it was.
     */
    suspend fun syncWithSession(): Boolean

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
