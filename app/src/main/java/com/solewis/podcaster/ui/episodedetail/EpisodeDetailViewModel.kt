package com.solewis.podcaster.ui.episodedetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeDetailItem
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlaybackStarter
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.PlayedMarker
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EpisodeDetailViewModel(
    private val episodeId: String,
    private val episodeRepository: EpisodeRepository,
    private val queueRepository: QueueRepository,
    private val playback: Playback,
    private val downloads: Downloads,
    private val playbackStarter: PlaybackStarter
) : ViewModel() {

    private val playedMarker = PlayedMarker(episodeRepository, playback)

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
        val liveDurationMillis: Long? = null,
        /** Null when this episode is not downloaded and nothing is in flight for it. */
        val download: EpisodeDownload? = null,
        /** Tapped play, no sound yet - see [com.solewis.podcaster.player.PlaybackStarter]. */
        val isStarting: Boolean = false
    )

    val state: StateFlow<UiState> = combine(
        episodeRepository.observeEpisodeDetail(episodeId),
        playback.state,
        playback.progress,
        downloads.observe(),
        playbackStarter.pendingEpisodeId
    ) { episode, playback, progress, downloadStates, pending ->
        val isThis = playback.episodeId == episodeId
        UiState(
            episode = episode,
            isLoading = false,
            isPlayingThis = isThis && playback.isPlaying,
            livePositionMillis = progress.positionMillis.takeIf { isThis },
            liveDurationMillis = progress.durationMillis?.takeIf { isThis },
            download = downloadStates[episodeId],
            isStarting = pending == episodeId
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Resumes/starts this episode, or pauses it if it's already the one playing. */
    fun togglePlay() {
        viewModelScope.launch {
            if (state.value.isPlayingThis) {
                playback.togglePlayPause()
                return@launch
            }
            val playable = episodeRepository.getPlayableById(episodeId) ?: return@launch
            playbackStarter.start(playable)
        }
    }

    fun enqueue() {
        viewModelScope.launch { queueRepository.enqueue(episodeId) }
    }

    /**
     * Marks this episode played or unplayed by hand.
     *
     * When it happens to be the episode currently loaded in the player, the mark alone is not
     * enough: [com.solewis.podcaster.player.ProgressWriter] re-derives `isPlayed` from the *live
     * player position* every five seconds, so it would quietly revert the mark - and nothing stored
     * in the database can prevent that. Seeking to the end instead makes every subsequent write
     * agree with the mark, ends the episode so auto-advance carries on, and is what "I'm done with
     * this one" means anyway.
     */
    fun togglePlayed() {
        val current = state.value
        val episode = current.episode ?: return
        viewModelScope.launch {
            playedMarker.setPlayed(
                episodeId,
                played = !episode.isPlayed,
                // The player's own duration when it has one: the feed's value is often wrong, and
                // seeking to a wrong duration would land somewhere other than the end.
                durationMillis = current.liveDurationMillis ?: episode.durationMillis
            )
        }
    }

    fun download() {
        viewModelScope.launch { downloads.download(episodeId) }
    }

    fun removeDownload() {
        viewModelScope.launch { downloads.remove(episodeId) }
    }
}
