package com.solewis.podcaster.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home = subscriptions (most-recently-listened first) up top, every episode across every show
 * (newest-published first) below - replacing what used to be two separate tabs (Library and All
 * Episodes). One glance at what you follow, one continuous feed of what's new, no tab switch
 * needed to get from "who do I listen to" to "what's next."
 */
class HomeViewModel(
    private val podcastRepository: PodcastRepository,
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    data class UiState(
        val subscriptions: List<HomeShowSummary> = emptyList(),
        val episodes: List<EpisodeFeedItem> = emptyList()
    )

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeHomeOrder(),
        episodeRepository.observeAllEpisodes()
    ) { subscriptions, episodes -> UiState(subscriptions, episodes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun play(episode: EpisodeFeedItem) {
        viewModelScope.launch {
            val playable = episodeRepository.getPlayable(episode.id, episode.podcastTitle, episode.podcastArtworkUrl)
                ?: return@launch
            playerConnection.play(playable)
        }
    }

    fun enqueue(episode: EpisodeFeedItem) {
        viewModelScope.launch { queueRepository.enqueue(episode.id) }
    }
}
