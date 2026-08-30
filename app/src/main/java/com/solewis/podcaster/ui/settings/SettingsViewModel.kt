package com.solewis.podcaster.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.data.settings.SettingsStore
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.data.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A pass-through onto [SettingsStore]. Worth having anyway rather than letting the screen write
 * preferences directly: the store is the single writer that everything else observes, and a screen
 * holding it would be a screen that can be recomposed into writing.
 */
class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        store.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), store.snapshot())

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

    fun setAutoPlayInCar(enabled: Boolean) {
        store.autoPlayInCar = enabled
    }
}
