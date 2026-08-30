package com.solewis.podcaster.data.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store is what makes a setting take effect without restarting anything: the playback service
 * re-pushes its notification buttons off [SettingsStore.observe], and `TimedSkipPlayer` reads the
 * amount per press. Both of those are silent if the flow never emits, which is what most of this
 * pins down.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun the_defaults_are_the_ones_the_app_shipped_with() {
        with(SettingsStore(context).snapshot()) {
            assertThat(skipBack).isEqualTo(SkipAmount.FIFTEEN)
            assertThat(skipForward).isEqualTo(SkipAmount.FIFTEEN)
            assertThat(theme).isEqualTo(ThemeMode.SYSTEM)
            assertThat(autoAdvance).isTrue()
            // Off unless asked for: sound starting by itself as an engine turns over is assertive
            // enough that it should be opted into.
            assertThat(autoPlayInCar).isFalse()
        }
    }

    @Test
    fun back_and_forward_are_stored_separately() {
        val store = SettingsStore(context)

        store.skipBack = SkipAmount.THIRTY
        store.skipForward = SkipAmount.FIVE

        // One key doing double duty would be invisible until someone set them to different values.
        assertThat(store.skipBack).isEqualTo(SkipAmount.THIRTY)
        assertThat(store.skipForward).isEqualTo(SkipAmount.FIVE)
    }

    @Test
    fun every_setting_survives_a_separate_reader() {
        SettingsStore(context).apply {
            skipBack = SkipAmount.FIVE
            theme = ThemeMode.DARK
            autoAdvance = false
            autoPlayInCar = true
        }

        // The service and the Activity each hold their own instance, so they have to agree.
        with(SettingsStore(context).snapshot()) {
            assertThat(skipBack).isEqualTo(SkipAmount.FIVE)
            assertThat(theme).isEqualTo(ThemeMode.DARK)
            assertThat(autoAdvance).isFalse()
            assertThat(autoPlayInCar).isTrue()
        }
    }

    @Test
    fun a_change_reaches_an_existing_observer() = runTest(mainDispatcher.dispatcher) {
        val store = SettingsStore(context)
        val observed = store.observe().stateIn(backgroundScope, SharingStarted.Eagerly, store.snapshot())
        keepHot(observed)

        store.skipForward = SkipAmount.THIRTY

        // Without this, changing the amount would leave the notification's button - and the car's -
        // still showing the old number until the service was restarted.
        assertThat(observed.awaitValue { it.skipForward == SkipAmount.THIRTY }.skipForward)
            .isEqualTo(SkipAmount.THIRTY)
    }

    @Test
    fun a_speed_change_does_not_wake_settings_observers() = runTest(mainDispatcher.dispatcher) {
        val store = SettingsStore(context)
        store.skipBack = SkipAmount.FIVE
        val observed = store.observe().stateIn(backgroundScope, SharingStarted.Eagerly, AppSettings())
        keepHot(observed)
        observed.awaitValue { it.skipBack == SkipAmount.FIVE }

        store.speed = 1.5f

        // Speed is written from a player callback, not a settings screen. Folding it in would make
        // the playback service re-push its notification buttons repeatedly during playback.
        assertThat(observed.value.skipBack).isEqualTo(SkipAmount.FIVE)
    }

    @Test
    fun an_unrecognised_stored_value_falls_back_rather_than_crashing() {
        // Reachable from a restored backup or a value written by a newer build. Throwing here would
        // mean an app that cannot start, for a preference.
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit()
            .putInt("skipBackSeconds", 7)
            .putString("theme", "SEPIA")
            .apply()

        with(SettingsStore(context).snapshot()) {
            assertThat(skipBack).isEqualTo(SkipAmount.FIFTEEN)
            assertThat(theme).isEqualTo(ThemeMode.SYSTEM)
        }
    }
}
