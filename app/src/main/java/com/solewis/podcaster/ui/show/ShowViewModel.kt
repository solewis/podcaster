package com.solewis.podcaster.ui.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.data.repo.RefreshResult
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.ui.common.formatDuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShowViewModel(
    private val podcastId: Long,
    private val podcastRepository: PodcastRepository,
    private val episodeRepository: EpisodeRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val queueRepository: QueueRepository,
    private val playback: Playback,
    private val downloads: Downloads
) : ViewModel() {

    data class UiState(
        val podcast: PodcastEntity? = null,
        val episodes: List<EpisodeListItem> = emptyList(),
        val jump: JumpPillUi? = null,
        val isLoading: Boolean = true
    )

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeById(podcastId),
        episodeRepository.observeEpisodes(podcastId)
    ) { podcast, episodes ->
        buildUiState(podcast, episodes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** Separate from [state] so a download progress tick doesn't recompose the whole episode list. */
    val downloadStates: StateFlow<Map<String, EpisodeDownload>> = downloads.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    /** Flips once the unsubscribe completes, so the screen can navigate back - there is nothing
     * left here to show once the podcast row (and its episodes) are gone. */
    /** How many episodes the last "mark all played" changed; null when there is nothing to report. */
    private val _markedAllPlayed = MutableStateFlow<Int?>(null)
    val markedAllPlayed: StateFlow<Int?> = _markedAllPlayed.asStateFlow()

    private val _didUnsubscribe = MutableStateFlow(false)
    val didUnsubscribe: StateFlow<Boolean> = _didUnsubscribe.asStateFlow()

    init {
        // Opening a show asks one question above all others - is there a new episode - so check,
        // rather than showing whatever was last fetched and waiting for the refresh button to be
        // found. Skipped when this show was checked recently, and a 304 when nothing changed.
        //
        // Deliberately invisible: it raises neither [isRefreshing] nor [refreshError]. Nobody asked
        // for it, so a feed that is down has no business throwing a snackbar over a screen that
        // opened fine, and sharing the spinner flag with refresh() would let this swallow a real
        // tap on the refresh button. The list is already on screen from Room and updates itself
        // when new rows land. Failures are still recorded on the podcast row.
        viewModelScope.launch { subscriptionRepository.refreshIfStale(podcastId) }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshError.value = null
            val result = subscriptionRepository.refresh(podcastId)
            if (result is RefreshResult.Failure) _refreshError.value = result.message
            _isRefreshing.value = false
        }
    }

    fun unsubscribe() {
        viewModelScope.launch {
            podcastRepository.unsubscribe(podcastId)
            _didUnsubscribe.value = true
        }
    }

    fun enqueue(episodeId: String) {
        viewModelScope.launch { queueRepository.enqueue(episodeId) }
    }

    /**
     * Clears the backlog. Emits the number marked so the screen can confirm it - an action that
     * silently changes a few hundred rows needs to say what it did.
     */
    fun markAllPlayed() {
        viewModelScope.launch {
            _markedAllPlayed.value = episodeRepository.markAllPlayed(podcastId)
        }
    }

    /** Cleared once shown, so the message does not reappear on every recomposition. */
    fun clearMarkedAllPlayed() {
        _markedAllPlayed.value = null
    }

    fun download(episodeId: String) {
        viewModelScope.launch { downloads.download(episodeId) }
    }

    fun removeDownload(episodeId: String) {
        viewModelScope.launch { downloads.remove(episodeId) }
    }

    fun toggleSortOrder() {
        val current = state.value.podcast?.sortOrder ?: return
        val next = if (current == SortOrder.NEWEST_FIRST) SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
        viewModelScope.launch { podcastRepository.setSortOrder(podcastId, next) }
    }

    fun play(episodeId: String) {
        val podcast = state.value.podcast ?: return
        viewModelScope.launch {
            val playable = episodeRepository.getPlayable(episodeId, podcast.title, podcast.artworkUrl) ?: return@launch
            playback.play(playable)
        }
    }


    private fun buildUiState(podcast: PodcastEntity?, episodes: List<EpisodeListItem>): UiState {
        if (podcast == null) return UiState(podcast = null, isLoading = episodes.isEmpty())

        val sorted = sortEpisodes(episodes, podcast.sortOrder)
        val target = JumpTargetResolver.resolve(episodes)
        val jump = target?.let { buildJumpPill(it, episodes, sorted) }

        return UiState(podcast = podcast, episodes = sorted, jump = jump, isLoading = false)
    }

    private fun sortEpisodes(episodes: List<EpisodeListItem>, sortOrder: SortOrder): List<EpisodeListItem> {
        val (numbered, unnumbered) = episodes.partition { it.chronoIndex != null }
        val sortedNumbered = when (sortOrder) {
            SortOrder.NEWEST_FIRST -> numbered.sortedByDescending { it.chronoIndex }
            SortOrder.OLDEST_FIRST -> numbered.sortedBy { it.chronoIndex }
        }
        // Trailers/bonus episodes (no chronoIndex) always trail the list, regardless of direction.
        return sortedNumbered + unnumbered
    }

    private fun buildJumpPill(
        target: JumpTargetResolver.Target,
        episodes: List<EpisodeListItem>,
        sorted: List<EpisodeListItem>
    ): JumpPillUi? {
        val episode = episodes.find { it.id == target.episodeId } ?: return null
        val itemIndex = sorted.indexOfFirst { it.id == target.episodeId }
        if (itemIndex == -1) return null

        val numberLabel = episode.displayNumber?.let { "Ep $it" } ?: ellipsize(episode.title)
        val (label, secondary) = when (target.intent) {
            JumpTargetResolver.Intent.RESUME -> {
                val remaining = episode.durationMillis?.let { (it - episode.positionMillis).coerceAtLeast(0) }
                "Resume $numberLabel" to remaining?.let { "${formatDuration(it)} left" }
            }
            JumpTargetResolver.Intent.NEXT ->
                "Next: $numberLabel" to formatDuration(episode.durationMillis)
            JumpTargetResolver.Intent.REVISIT ->
                "Last played: $numberLabel" to null
        }

        return JumpPillUi(
            episodeId = target.episodeId,
            itemIndex = itemIndex,
            intent = target.intent,
            label = label,
            secondary = secondary
        )
    }

    private fun ellipsize(title: String, maxLength: Int = 28): String =
        if (title.length <= maxLength) title else title.take(maxLength - 1) + "…"
}
