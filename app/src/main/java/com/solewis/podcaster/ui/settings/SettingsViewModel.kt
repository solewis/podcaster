package com.solewis.podcaster.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.repo.Downloads
import com.solewis.podcaster.data.repo.StreamCache
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.data.settings.PrefetchMode
import com.solewis.podcaster.data.settings.SettingsStore
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.player.PlaybackLog
import com.solewis.podcaster.data.settings.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A pass-through onto [SettingsStore]. Worth having anyway rather than letting the screen write
 * preferences directly: the store is the single writer that everything else observes, and a screen
 * holding it would be a screen that can be recomposed into writing.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val playbackLog: PlaybackLog,
    private val streamCache: StreamCache,
    private val downloads: Downloads
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        store.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.snapshot())

    private val _streamCacheBytes = MutableStateFlow(0L)
    val streamCacheBytes: StateFlow<Long> = _streamCacheBytes.asStateFlow()

    private val _downloadBytes = MutableStateFlow(0L)
    val downloadBytes: StateFlow<Long> = _downloadBytes.asStateFlow()

    init {
        viewModelScope.launch { refreshStorageStats() }
    }

    fun setSkipBack(amount: SkipAmount) {
        store.skipBack = amount
    }

    fun setSkipForward(amount: SkipAmount) {
        store.skipForward = amount
    }

    fun setTheme(mode: ThemeMode) {
        store.theme = mode
    }

    fun setAutoAdvance(enabled: Boolean) {
        store.autoAdvance = enabled
    }

    fun setPrefetchMode(mode: PrefetchMode) {
        store.prefetchMode = mode
    }

    fun setPrefetchWifiOnly(enabled: Boolean) {
        store.prefetchWifiOnly = enabled
    }

    /**
     * The playback log, for sharing. Read on demand rather than observed: it is a diagnostic
     * someone opens once, not state the screen tracks.
     *
     * Returned as text so sharing needs no `FileProvider` and no granted URI - the log is a few
     * kilobytes of plain lines, and `ACTION_SEND` carries it directly.
     */
    fun playbackLogText(): String = playbackLog.snapshot()

    fun clearPlaybackLog() = playbackLog.clear()

    fun clearStreamCache() {
        viewModelScope.launch {
            streamCache.clear()
            refreshStorageStats()
        }
    }

    fun removeAllDownloads() {
        viewModelScope.launch {
            downloads.removeAll()
            refreshStorageStats()
        }
    }

    private suspend fun refreshStorageStats() {
        _streamCacheBytes.value = streamCache.sizeBytes()
        _downloadBytes.value = downloads.downloadedBytes()
    }
}
