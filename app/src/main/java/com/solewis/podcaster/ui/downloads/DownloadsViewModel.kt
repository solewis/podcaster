package com.solewis.podcaster.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.player.PlaybackStarter
import com.solewis.podcaster.player.Playback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What is on the device, and what it costs. The two questions someone opens this screen to answer
 * are "is that finished yet" and "what do I delete to get space back", which is what the ordering
 * serves - not date, which is what the rest of the app sorts by.
 */
class DownloadsViewModel(
    private val episodeRepository: EpisodeRepository,
    private val downloads: Downloads,
    private val playback: Playback,
    private val playbackStarter: PlaybackStarter
) : ViewModel() {

    data class Row(val episode: EpisodeFeedItem, val download: EpisodeDownload)

    /**
     * The episode metadata is joined in from the cross-show feed the Home screen already observes,
     * rather than looked up per download. Media3's index knows only ids and byte counts - it has
     * never heard of a title - and the feed flow is already reactive, so a title correction from a
     * refresh shows up here too.
     */
    val rows: StateFlow<List<Row>> = combine(
        episodeRepository.observeAllEpisodes(),
        downloads.observe()
    ) { episodes, downloadStates ->
        val byId = episodes.associateBy { it.id }
        downloadStates.values
            // An episode whose show was unsubscribed leaves its rows behind in the download index
            // with nothing to render. Dropping it here keeps the list honest; deleting the file is
            // the storage-reclaim work still to come.
            .mapNotNull { download -> byId[download.episodeId]?.let { Row(it, download) } }
            .sortedWith(
                // Anything still in flight first - that is what someone on this screen is
                // watching. Then finished downloads biggest-first, which is the order you want
                // when the reason you opened it was to free space.
                compareBy<Row> { it.download.status == DownloadStatus.DOWNLOADED }
                    .thenByDescending { it.download.bytesDownloaded }
            )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    init {
        // Recomputed off the download states rather than summed from [rows], so it still counts
        // episodes whose show has been unsubscribed - otherwise the figure on screen would
        // disagree with the phone's own storage settings.
        viewModelScope.launch {
            downloads.observe().collect { _totalBytes.value = downloads.downloadedBytes() }
        }
    }

    fun play(episodeId: String) {
        viewModelScope.launch {
            episodeRepository.getPlayableById(episodeId)?.let { playbackStarter.start(it) }
        }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch { downloads.remove(episodeId) }
    }

    /** For a download that failed - the only state on this screen where the button re-downloads. */
    fun retry(episodeId: String) {
        viewModelScope.launch { downloads.download(episodeId) }
    }
}
