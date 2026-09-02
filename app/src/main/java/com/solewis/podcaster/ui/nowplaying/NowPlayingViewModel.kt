package com.solewis.podcaster.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.Playback
import com.solewis.podcaster.player.ProgressUiState
import com.solewis.podcaster.player.PlaybackStarter
import com.solewis.podcaster.player.SleepTimer
import com.solewis.podcaster.player.SleepTimerState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NowPlayingViewModel(
    private val playback: Playback,
    /**
     * The settings as a `Flow` rather than the store itself, so the screen's skip buttons can be
     * driven without this ViewModel needing a `Context` - and so a test can hand it a fixed value.
     */
    settings: Flow<AppSettings>,
    private val sleepTimer: SleepTimer,
    private val playbackStarter: PlaybackStarter
) : ViewModel() {

    val playbackState: StateFlow<PlaybackUiState> = playback.state
    val progress: StateFlow<ProgressUiState> = playback.progress

    /** Waiting on data for long enough to be worth a spinner - see [Playback.isStalled]. */
    val isStalled: StateFlow<Boolean> = playback.isStalled

    /** The skip buttons print the amount, so they have to read the setting, not a constant. */
    val settings: StateFlow<AppSettings> =
        settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    /** Straight from the timer, which outlives this ViewModel - see [SleepTimer]. */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes * 60_000L)

    fun startSleepTimerAtEndOfEpisode() = sleepTimer.startEndOfEpisode()

    fun extendSleepTimer(minutes: Int) = sleepTimer.extend(minutes * 60_000L)

    fun cancelSleepTimer() = sleepTimer.cancel()

    fun togglePlayPause() {
        viewModelScope.launch { playbackStarter.togglePlayPause() }
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
