package com.solewis.podcaster.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.ProgressUiState
import com.solewis.podcaster.player.SleepTimer
import com.solewis.podcaster.player.SleepTimerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NowPlayingViewModel(
    private val playback: Playback,
    private val sleepTimer: SleepTimer
) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playback.state
    val progress: StateFlow<ProgressUiState> = playback.progress

    /** Straight from the timer, which outlives this ViewModel - see [SleepTimer]. */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    fun startSleepTimerAtEndOfEpisode() = sleepTimer.startEndOfEpisode()

    fun extendSleepTimer(minutes: Int) = sleepTimer.extend(minutes * 60_000L)

    fun cancelSleepTimer() = sleepTimer.cancel()

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
