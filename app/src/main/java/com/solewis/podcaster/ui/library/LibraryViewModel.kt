package com.solewis.podcaster.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.repo.PodcastRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val podcastRepository: PodcastRepository) : ViewModel() {
    val podcasts: StateFlow<List<PodcastEntity>> = podcastRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unsubscribe(podcastId: Long) {
        viewModelScope.launch { podcastRepository.unsubscribe(podcastId) }
    }
}
