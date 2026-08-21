package com.solewis.podcaster.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.SubscriptionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val podcastRepository: PodcastRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {
    val podcasts: StateFlow<List<PodcastEntity>> = podcastRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun unsubscribe(podcastId: Long) {
        viewModelScope.launch { podcastRepository.unsubscribe(podcastId) }
    }

    fun refreshAll() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            subscriptionRepository.refreshAll()
            _isRefreshing.value = false
        }
    }
}
