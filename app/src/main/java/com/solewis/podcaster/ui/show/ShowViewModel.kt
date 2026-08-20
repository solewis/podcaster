package com.solewis.podcaster.ui.show

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.player.PlayerConnection
import com.solewis.podcaster.ui.common.formatDuration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ShowViewModel(
    private val podcastId: Long,
    private val podcastRepository: PodcastRepository,
    private val episodeRepository: EpisodeRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    data class UiState(
        val podcast: PodcastEntity? = null,
        val items: List<ShowListItem> = emptyList(),
        val jump: JumpPillUi? = null,
        val isLoading: Boolean = true
    )

    val state: StateFlow<UiState> = combine(
        podcastRepository.observeById(podcastId),
        episodeRepository.observeEpisodes(podcastId)
    ) { podcast, episodes ->
        buildUiState(podcast, episodes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun toggleSortOrder() {
        val current = state.value.podcast?.sortOrder ?: return
        val next = if (current == SortOrder.NEWEST_FIRST) SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
        viewModelScope.launch { podcastRepository.setSortOrder(podcastId, next) }
    }

    /**
     * DEBUG ONLY - stands in for the real player (Phase 4+) so the jump-to-last-listened feature
     * is demonstrable and testable before any playback code exists. Delete this along with its
     * call sites in `ShowScreen`/`EpisodeRow` once `ProgressWriter` lands in Phase 5.
     */
    fun debugSetProgress(episodeId: String, positionMillis: Long, isPlayed: Boolean) {
        viewModelScope.launch { episodeRepository.setProgress(episodeId, positionMillis, isPlayed) }
    }

    fun play(episodeId: String) {
        val podcastTitle = state.value.podcast?.title ?: return
        viewModelScope.launch {
            val playable = episodeRepository.getPlayable(episodeId, podcastTitle) ?: return@launch
            playerConnection.play(playable)
        }
    }

    private fun buildUiState(podcast: PodcastEntity?, episodes: List<EpisodeListItem>): UiState {
        if (podcast == null) return UiState(podcast = null, isLoading = episodes.isEmpty())

        val sorted = sortEpisodes(episodes, podcast.sortOrder)
        val items = buildList {
            add(ShowListItem.Header(podcast, episodes.size))
            sorted.forEach { add(ShowListItem.Episode(it)) }
        }

        val target = JumpTargetResolver.resolve(episodes)
        val jump = target?.let { buildJumpPill(it, episodes, items) }

        return UiState(podcast = podcast, items = items, jump = jump, isLoading = false)
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
        items: List<ShowListItem>
    ): JumpPillUi? {
        val episode = episodes.find { it.id == target.episodeId } ?: return null
        val itemIndex = items.indexOfFirst { it is ShowListItem.Episode && it.item.id == target.episodeId }
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
