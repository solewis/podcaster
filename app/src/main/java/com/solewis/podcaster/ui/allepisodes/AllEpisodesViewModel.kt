package com.solewis.podcaster.ui.allepisodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AllEpisodesViewModel(
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    val episodes: StateFlow<List<EpisodeFeedItem>> = episodeRepository.observeAllEpisodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
