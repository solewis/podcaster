package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.ProgressUiState
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
            isPlaying = false
        )
        _progress.value = ProgressUiState(episode.startPositionMillis, episode.durationMillis)
    }

    override suspend fun togglePlayPause() {
        togglePlayPauseCount++
    }

    override suspend fun pause() {
        pauseCount++
        _state.value = _state.value.copy(isPlaying = false)
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
        _state.value = _state.value.copy(episodeId = episodeId, isPlaying = true)
    }

    /** Loaded but silent - what a pause looks like, and what a stalled tap looks like too. */
    fun emitPaused(episodeId: String) {
        _state.value = _state.value.copy(episodeId = episodeId, isPlaying = false)
    }

    fun emitProgress(positionMillis: Long, durationMillis: Long? = null) {
        _progress.value = ProgressUiState(positionMillis, durationMillis)
    }
}
