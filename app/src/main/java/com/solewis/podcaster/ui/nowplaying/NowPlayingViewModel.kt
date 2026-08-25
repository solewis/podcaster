package com.solewis.podcaster.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.ProgressUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NowPlayingViewModel(private val playback: Playback) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playback.state
    val progress: StateFlow<ProgressUiState> = playback.progress

    fun togglePlayPause() {
        viewModelScope.launch { playback.togglePlayPause() }
    }

    fun seekTo(positionMillis: Long) {
        viewModelScope.launch { playback.seekTo(positionMillis) }
    }

    fun skipForward() {
        viewModelScope.launch { playback.skipForward() }
    }

    fun skipBack() {
        viewModelScope.launch { playback.skipBack() }
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { playback.setSpeed(speed) }
    }
}
