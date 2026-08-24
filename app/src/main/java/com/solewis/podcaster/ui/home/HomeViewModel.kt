package com.solewis.podcaster.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.MutableStateFlow
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
        val episodes: List<EpisodeFeedItem> = emptyList(),
        /** True only until Room's first emission arrives - distinguishes "still loading" from
         * "genuinely no subscriptions yet", which otherwise look identical (both empty lists) and
         * would flash the empty-state message on every launch before the real data loads in. */
        val isLoading: Boolean = true,
        /** Set the instant a row's play button is tapped, cleared once that episode is actually
         * audible - see [play]. Covers the real dead time (controller connection, network
         * buffering) between tap and sound, which a play button alone gives no feedback for. */
        val loadingEpisodeId: String? = null,
        val nowPlayingEpisodeId: String? = null,
        val nowPlayingPositionMillis: Long = 0,
        val nowPlayingDurationMillis: Long? = null
    )

    private val loadingEpisodeId = MutableStateFlow<String?>(null)

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeHomeOrder(),
        episodeRepository.observeAllEpisodes(),
        playerConnection.state,
        playerConnection.progress,
        loadingEpisodeId
    ) { subscriptions, episodes, playback, progress, loading ->
        UiState(
            subscriptions = subscriptions,
            episodes = episodes,
            isLoading = false,
            // Cleared as soon as this exact episode is confirmed playing - a stale tap on a
            // different row (or one that never started) must not leave a spinner stuck forever.
            loadingEpisodeId = loading.takeUnless { it == playback.episodeId && playback.isPlaying },
            nowPlayingEpisodeId = playback.episodeId.takeIf { playback.isPlaying },
            nowPlayingPositionMillis = progress.positionMillis,
            nowPlayingDurationMillis = progress.durationMillis
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun play(episode: EpisodeFeedItem) {
        loadingEpisodeId.value = episode.id
        viewModelScope.launch {
            val playable = episodeRepository.getPlayable(episode.id, episode.podcastTitle, episode.podcastArtworkUrl)
            if (playable == null) {
                loadingEpisodeId.value = null
                return@launch
            }
            playerConnection.play(playable)
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch { playerConnection.togglePlayPause() }
    }

    fun enqueue(episode: EpisodeFeedItem) {
        viewModelScope.launch { queueRepository.enqueue(episode.id) }
    }

    suspend fun descriptionFor(episodeId: String): String? = episodeRepository.getDescription(episodeId)
}
