package com.solewis.podcaster.ui.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Phase 2 scope only: a plain episode list, in the order [EpisodeRepository] returns it (newest
 * first). The sort toggle and the "jump to last listened" headline feature are Phase 3.
 */
class ShowViewModel(
    podcastId: Long,
    podcastRepository: PodcastRepository,
    episodeRepository: EpisodeRepository
) : ViewModel() {

    data class UiState(
        val podcast: PodcastEntity? = null,
        val episodes: List<EpisodeListItem> = emptyList(),
        val isLoading: Boolean = true
    )

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeById(podcastId),
        episodeRepository.observeEpisodes(podcastId)
    ) { podcast, episodes ->
        UiState(podcast = podcast, episodes = episodes, isLoading = false)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())
}
