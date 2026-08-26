package com.solewis.podcaster.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlayedMarker
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
    private val playback: Playback,
    private val downloads: Downloads
) : ViewModel() {

    private val playedMarker = PlayedMarker(episodeRepository, playback)

    /** Separate from [UiState] so a download progress tick doesn't recompose the whole feed. */
    val downloadStates: StateFlow<Map<String, EpisodeDownload>> =
        downloads.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    init {
        // Genuinely clears the pending tap rather than merely hiding it while `isPlaying` holds.
        // Masking it in the combine instead looked identical at first - until you paused, which
        // un-masked the stale id and put the spinner back on an episode that had long since
        // started. Also clears when some *other* episode takes over, so a tap that never
        // produced sound can't leave a spinner stuck forever.
        viewModelScope.launch {
            playback.state.collect { playback ->
                val pending = loadingEpisodeId.value ?: return@collect
                if (playback.episodeId == pending && playback.isPlaying) {
                    loadingEpisodeId.value = null
                } else if (playback.episodeId != null && playback.episodeId != pending) {
                    loadingEpisodeId.value = null
                }
            }
        }
    }

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeHomeOrder(),
        episodeRepository.observeAllEpisodes(),
        playback.state,
        playback.progress,
        loadingEpisodeId
    ) { subscriptions, episodes, playback, progress, loading ->
        UiState(
            subscriptions = subscriptions,
            episodes = episodes,
            isLoading = false,
            loadingEpisodeId = loading,
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
            playback.play(playable)
        }
    }

    fun togglePlayPause() {
        viewModelScope.launch { playback.togglePlayPause() }
    }

    fun enqueue(episode: EpisodeFeedItem) {
        viewModelScope.launch { queueRepository.enqueue(episode.id) }
    }


    fun download(episodeId: String) {
        viewModelScope.launch { downloads.download(episodeId) }
    }

    fun removeDownload(episodeId: String) {
        viewModelScope.launch { downloads.remove(episodeId) }
    }

    fun togglePlayed(episode: EpisodeFeedItem) {
        viewModelScope.launch {
            playedMarker.setPlayed(episode.id, played = !episode.isPlayed, durationMillis = episode.durationMillis)
        }
    }
}
