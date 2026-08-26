package com.solewis.podcaster.ui.settings

import android.content.Context
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.settings.SettingsStore
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.data.settings.ThemeMode
import com.solewis.podcaster.testing.ViewModelHost
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen writes straight through to the store that the player and the notification read, so the
 * risk is not that a tap does nothing - it is that a tap changes the wrong thing. Back and forward
 * are two identical-looking rows differing only in direction, which is exactly the kind of pair a
 * copy-paste gets wrong.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = SettingsStore(context)
    private val host = ViewModelHost()

    @After
    fun tearDown() = host.close()

    private fun launch() {
        val viewModel = host.hosting(SettingsViewModel(store))
        compose.setContent { PodcasterTheme { SettingsScreen(viewModel = viewModel, onBack = {}) } }
    }

    @Test
    fun the_current_amounts_are_shown_as_selected() {
        store.skipBack = SkipAmount.THIRTY
        store.skipForward = SkipAmount.FIVE

        launch()

        compose.onNodeWithTag(TestTags.skipChoice(forward = false, amount = SkipAmount.THIRTY)).assertIsSelected()
        compose.onNodeWithTag(TestTags.skipChoice(forward = true, amount = SkipAmount.FIVE)).assertIsSelected()
    }

    @Test
    fun choosing_a_skip_back_amount_leaves_skip_forward_alone() {
        launch()

        compose.onNodeWithTag(TestTags.skipChoice(forward = false, amount = SkipAmount.THIRTY)).performClick()
        compose.waitForIdle()

        assertThat(store.skipBack).isEqualTo(SkipAmount.THIRTY)
        assertThat(store.skipForward).isEqualTo(SkipAmount.FIFTEEN)
    }

    @Test
    fun choosing_a_skip_forward_amount_leaves_skip_back_alone() {
        launch()

        compose.onNodeWithTag(TestTags.skipChoice(forward = true, amount = SkipAmount.FIVE)).performClick()
        compose.waitForIdle()

        assertThat(store.skipForward).isEqualTo(SkipAmount.FIVE)
        assertThat(store.skipBack).isEqualTo(SkipAmount.FIFTEEN)
    }

    @Test
    fun the_selection_moves_to_whatever_was_just_tapped() {
        launch()

        compose.onNodeWithTag(TestTags.skipChoice(forward = false, amount = SkipAmount.FIVE)).performClick()
        compose.waitForIdle()

        // Reads back through observe(), so this also proves the screen re-renders from the store
        // rather than from its own local copy of the choice.
        compose.onNodeWithTag(TestTags.skipChoice(forward = false, amount = SkipAmount.FIVE)).assertIsSelected()
    }

    @Test
    fun picking_a_theme_stores_it() {
        launch()

        compose.onNodeWithTag(TestTags.themeChoice(ThemeMode.DARK)).performClick()
        compose.waitForIdle()

        assertThat(store.theme).isEqualTo(ThemeMode.DARK)
        compose.onNodeWithTag(TestTags.themeChoice(ThemeMode.DARK)).assertIsSelected()
    }

    @Test
    fun auto_advance_starts_on_and_can_be_turned_off() {
        launch()

        // Scrolled into view first: it is the last row on a scrolling screen, and on a small
        // viewport it starts below the fold, where an injected tap lands on nothing.
        compose.onNodeWithTag(TestTags.AUTO_ADVANCE_SWITCH).performScrollTo().assertIsOn()
        compose.onNodeWithTag(TestTags.AUTO_ADVANCE_SWITCH).performClick()
        compose.waitForIdle()

        assertThat(store.autoAdvance).isFalse()
        compose.onNodeWithTag(TestTags.AUTO_ADVANCE_SWITCH).assertIsOff()
    }
}
