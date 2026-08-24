package com.solewis.podcaster.ui.episodedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeDetailItem
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EpisodeDetailViewModel(
    private val episodeId: String,
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    data class UiState(
        val episode: EpisodeDetailItem? = null,
        val isLoading: Boolean = true,
        /** True only while *this* episode is the one making sound. */
        val isPlayingThis: Boolean = false,
        /**
         * Player position, used in preference to the episode's persisted one while this episode
         * is playing - so the bar on screen keeps moving rather than stepping every ~5s when the
         * progress writer next flushes.
         */
        val livePositionMillis: Long? = null,
        val liveDurationMillis: Long? = null
    )

    val state: StateFlow<UiState> = combine(
        episodeRepository.observeEpisodeDetail(episodeId),
        playerConnection.state,
        playerConnection.progress
    ) { episode, playback, progress ->
        val isThis = playback.episodeId == episodeId
        UiState(
            episode = episode,
            isLoading = false,
            isPlayingThis = isThis && playback.isPlaying,
            livePositionMillis = progress.positionMillis.takeIf { isThis },
            liveDurationMillis = progress.durationMillis?.takeIf { isThis }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Resumes/starts this episode, or pauses it if it's already the one playing. */
    fun togglePlay() {
        viewModelScope.launch {
            if (state.value.isPlayingThis) {
                playerConnection.togglePlayPause()
                return@launch
            }
            val playable = episodeRepository.getPlayableById(episodeId) ?: return@launch
            playerConnection.play(playable)
        }
    }

    fun enqueue() {
        viewModelScope.launch { queueRepository.enqueue(episodeId) }
    }
}
