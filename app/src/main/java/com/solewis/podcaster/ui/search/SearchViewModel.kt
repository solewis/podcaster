package com.solewis.podcaster.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.repo.PodcastSearchResult
import com.solewis.podcaster.data.repo.SearchRepository
import com.solewis.podcaster.data.repo.SubscribeResult
import com.solewis.podcaster.data.repo.SubscriptionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val results: List<PodcastSearchResult> = emptyList(),
        val isSearching: Boolean = false,
        val error: String? = null,
        val subscribingFeedUrl: String? = null,
        val subscribedFeedUrls: Set<String> = emptySet()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        searchJob?.cancel()

        if (query.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), isSearching = false, error = null)
            return
        }

        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MILLIS)
            _state.value = _state.value.copy(isSearching = true, error = null)
            try {
                val results = searchRepository.search(query)
                _state.value = _state.value.copy(results = results, isSearching = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isSearching = false, error = e.message ?: "Search failed")
            }
        }
    }

    fun subscribe(result: PodcastSearchResult) {
        viewModelScope.launch {
            _state.value = _state.value.copy(subscribingFeedUrl = result.feedUrl, error = null)
            val outcome = subscriptionRepository.subscribe(
                feedUrl = result.feedUrl,
                itunesCollectionId = result.itunesCollectionId,
                seedTitle = result.title,
                seedArtworkUrl = result.artworkUrl
            )
            _state.value = when (outcome) {
                is SubscribeResult.Success, is SubscribeResult.AlreadySubscribed -> _state.value.copy(
                    subscribingFeedUrl = null,
                    subscribedFeedUrls = _state.value.subscribedFeedUrls + result.feedUrl
                )
                is SubscribeResult.Failure -> _state.value.copy(subscribingFeedUrl = null, error = outcome.message)
            }
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 400L
    }
}
