package com.solewis.podcaster.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.db.model.QueueItem
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.player.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class QueueViewModel(
    private val queueRepository: QueueRepository,
    private val playerConnection: PlayerConnection
) : ViewModel() {

    val items: StateFlow<List<QueueItem>> = queueRepository.observeQueue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun moveUp(queueId: Long) {
        viewModelScope.launch { queueRepository.moveUp(queueId) }
    }

    fun moveDown(queueId: Long) {
        viewModelScope.launch { queueRepository.moveDown(queueId) }
    }

    fun remove(queueId: Long) {
        viewModelScope.launch { queueRepository.remove(queueId) }
    }

    /** Plays this item right now, taking it out of the queue rather than waiting its turn. */
    fun playNow(item: QueueItem) {
        viewModelScope.launch {
            val playable = queueRepository.getPlayable(item.episodeId) ?: return@launch
            queueRepository.remove(item.queueId)
            playerConnection.play(playable)
        }
    }
}
