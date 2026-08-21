package com.solewis.podcaster.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.PlayerConnection
import com.solewis.podcaster.player.ProgressUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NowPlayingViewModel(private val playerConnection: PlayerConnection) : ViewModel() {

    val playback: StateFlow<PlaybackUiState> = playerConnection.state
    val progress: StateFlow<ProgressUiState> = playerConnection.progress

    fun togglePlayPause() {
        viewModelScope.launch { playerConnection.togglePlayPause() }
    }

    fun seekTo(positionMillis: Long) {
        viewModelScope.launch { playerConnection.seekTo(positionMillis) }
    }

    fun skipForward() {
        viewModelScope.launch { playerConnection.skipForward() }
    }

    fun skipBack() {
        viewModelScope.launch { playerConnection.skipBack() }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { playerConnection.setSpeed(speed) }
    }
}
