package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.ProgressUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [Playback] that records what it was asked to do and lets a test drive playback state directly.
 *
 * Both halves matter. [played] and friends answer "did the screen ask for the right thing"; the
 * `emit*` methods answer the harder question - how a screen reacts to playback changing underneath
 * it, which is where the real bugs have been. Note that [play] deliberately does *not* start
 * reporting playback on its own: the real thing takes time to connect and buffer, and a fake that
 * became audible instantly would hide exactly the in-between state the loading spinner exists for.
 */
class FakePlayback : Playback {

    private val _state = MutableStateFlow(PlaybackUiState())
    override val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(ProgressUiState())
    override val progress: StateFlow<ProgressUiState> = _progress.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    override val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _isStalled = MutableStateFlow(false)

    /**
     * Set directly rather than derived from a timer. The real one only turns true after half a
     * second of buffering; a test asserting what a stall looks like has no business also asserting
     * how long it takes to decide there is one - that belongs to [PlayerConnection]'s own test.
     */
    override val isStalled: StateFlow<Boolean> = _isStalled.asStateFlow()

    /** Buffering has gone on long enough that the UI should say so. */
    fun emitStalled(stalled: Boolean) {
        _isStalled.value = stalled
    }

    /** As if the buffer ran dry with no network left to refill it. */
    fun emitError(message: String) {
        _errors.tryEmit(message)
    }

    val played = mutableListOf<PlayableEpisode>()
    val restored = mutableListOf<PlayableEpisode>()
    var togglePlayPauseCount = 0
        private set
    var pauseCount = 0
        private set
    val seekedTo = mutableListOf<Long>()
    var skipForwardCount = 0
        private set
    var skipBackCount = 0
        private set
    val speedsSet = mutableListOf<Float>()

    override suspend fun play(episode: PlayableEpisode) {
        played += episode
    }

    /**
     * What the service would report. Defaults to "nothing running", the ordinary case; a test that
     * wants the notification-started-it case sets [liveSessionEpisodeId] first.
     */
    var liveSessionEpisodeId: String? = null
    var syncWithSessionCount = 0
        private set

    override suspend fun syncWithSession(): Boolean {
        syncWithSessionCount++
        val episodeId = liveSessionEpisodeId ?: return false
        // Adopting means the session's own truth wins, playing included - the whole point being
        // that it may well be playing when the app knew nothing about it.
        _state.value = _state.value.copy(episodeId = episodeId, isPlaying = true, playWhenReady = true)
        return true
    }

    /**
     * Mirrors the real thing: the episode becomes what the UI shows - paused, at its saved
     * position - without any player having loaded it.
     */
    override suspend fun restore(episode: PlayableEpisode) {
        restored += episode
        _state.value = _state.value.copy(
            episodeId = episode.episodeId,
            title = episode.title,
            podcastTitle = episode.podcastTitle,
            artworkUrl = episode.artworkUrl,
            isPlaying = false,
            playWhenReady = false
        )
        _progress.value = ProgressUiState(episode.startPositionMillis, episode.durationMillis)
    }

    override suspend fun togglePlayPause() {
        togglePlayPauseCount++
    }

    override suspend fun pause() {
        pauseCount++
        _state.value = _state.value.copy(isPlaying = false, playWhenReady = false)
    }

    override suspend fun seekTo(positionMillis: Long) {
        seekedTo += positionMillis
    }

    override suspend fun skipForward() {
        skipForwardCount++
    }

    override suspend fun skipBack() {
        skipBackCount++
    }

    override suspend fun setSpeed(speed: Float) {
        speedsSet += speed
    }

    // ---- driving playback state from a test ----

    /** The episode is now the loaded one and audible. */
    fun emitPlaying(episodeId: String) {
        _state.value = _state.value.copy(episodeId = episodeId, isPlaying = true, playWhenReady = true)
    }

    /** Loaded but silent - what a pause looks like, and what a stalled tap looks like too. */
    fun emitPaused(episodeId: String) {
        _state.value = _state.value.copy(episodeId = episodeId, isPlaying = false, playWhenReady = false)
    }

    /**
     * Mid-seek: still meant to be playing, momentarily not making sound.
     *
     * A real seek drops ExoPlayer into `STATE_BUFFERING`, so `isPlaying` goes false while
     * `playWhenReady` stays true - verified on device. This fake had no way to express that, which
     * is why the play/pause icon flickering on every scrub was invisible to the whole UI test
     * suite: `emitPlaying`/`emitPaused` could only describe the two steady states.
     */
    fun emitSeekBuffering(episodeId: String) {
        _state.value = _state.value.copy(
            episodeId = episodeId, isPlaying = false, playWhenReady = true, isBuffering = true
        )
    }

    fun emitProgress(positionMillis: Long, durationMillis: Long? = null) {
        _progress.value = ProgressUiState(positionMillis, durationMillis)
    }
}
