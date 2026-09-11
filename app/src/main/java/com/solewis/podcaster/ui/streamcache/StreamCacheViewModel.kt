package com.solewis.podcaster.ui.streamcache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.repo.CachedEpisode
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.StreamCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What specifically is filling up the 512MB rolling stream cache.
 *
 * Exists because a size limit and an LRU evictor answer "how much" and "which one goes first
 * under pressure", not "what is actually in there right now" - and the whole reason to look is to
 * find something taking up room nobody has any intention of finishing (an unsubscribed show's
 * stray episode, say) and get rid of just that one.
 */
class StreamCacheViewModel(
    private val episodeRepository: EpisodeRepository,
    private val streamCache: StreamCache
) : ViewModel() {

    /** [episode] is null for a cache entry whose show has since been unsubscribed - shown rather
     * than dropped, since that is exactly the kind of stray entry this screen exists to surface. */
    data class Row(val episode: EpisodeFeedItem?, val entry: CachedEpisode)

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    private val _totalBytes = MutableStateFlow(0L)
    val totalBytes: StateFlow<Long> = _totalBytes.asStateFlow()

    init {
        viewModelScope.launch { refresh() }
    }

    fun remove(episodeId: String) {
        viewModelScope.launch {
            streamCache.remove(episodeId)
            refresh()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            streamCache.clear()
            refresh()
        }
    }

    private suspend fun refresh() {
        val entries = streamCache.entries()
        val byId = episodeRepository.observeAllEpisodes().first().associateBy { it.id }
        _rows.value = entries
            .map { Row(byId[it.episodeId], it) }
            .sortedByDescending { it.entry.sizeBytes }
        _totalBytes.value = entries.sumOf { it.sizeBytes }
    }
}
