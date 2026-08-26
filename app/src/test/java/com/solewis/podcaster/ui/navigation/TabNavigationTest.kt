package com.solewis.podcaster.ui.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTag
import com.solewis.podcaster.testing.awaitText
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.clickEpisodeRow
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The tab-navigation rules, which took three attempts to get right and had no test either time.
 *
 * The rule settled on: tapping a tab always lands on that tab's own root screen, but the state
 * *within* that tab (which segment, where you had scrolled) survives. The two obvious
 * implementations both fail one half of that - `saveState`/`restoreState` alone restores the whole
 * popped stack and drops you back into a detail screen you had wandered into, while turning them
 * off throws away the segment and scroll position too.
 */
@RunWith(AndroidJUnit4::class)
class TabNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(podcastId, "1", durationMillis = 600_000),
                episodeRow(podcastId, "2", durationMillis = 600_000)
            )
        }
    }

    @After
    fun tearDown() = graph.close()

    private fun launchApp() {
        val container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
    }

    private fun tapTab(label: String) {
        compose.onNodeWithTag(TestTags.navTab(label)).performClick()
        compose.waitForIdle()
    }

    private fun tapSegment(label: String) {
        compose.onNodeWithTag(TestTags.segment(label)).performClick()
        compose.waitForIdle()
    }

    private fun openShowFromSubscriptions() {
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").performClick()
        compose.waitForIdle()
    }

    private fun assertOnTabRoot(title: String) {
        compose.awaitTag(TestTags.screenTitle(title))
        compose.onNodeWithTag(TestTags.screenTitle(title)).assertIsDisplayed()
    }

    @Test
    fun the_app_starts_on_the_library_tab() {
        launchApp()

        assertOnTabRoot("Library")
    }

    @Test
    fun each_tab_reaches_its_own_root() {
        launchApp()

        tapTab("Activity")
        assertOnTabRoot("Activity")

        tapTab("Search")
        assertOnTabRoot("Search")

        tapTab("Home")
        assertOnTabRoot("Library")
    }

    @Test
    fun tapping_home_from_an_episode_detail_goes_home() {
        launchApp()
        compose.awaitText("Episode 2")
        compose.clickEpisodeRow("Episode 2")
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.EPISODE_DETAIL_SCREEN).assertIsDisplayed()

        tapTab("Home")

        // Previously did nothing at all: popUpTo(saveState) immediately followed by restoreState
        // saved the detail screen and put it straight back.
        assertOnTabRoot("Library")
        compose.onAllNodesWithTag(TestTags.EPISODE_DETAIL_SCREEN).assertCountEquals(0)
    }

    @Test
    fun leaving_a_tab_from_a_detail_screen_returns_you_to_the_tab_not_the_detail() {
        launchApp()
        tapTab("Activity")
        tapSegment("Subscriptions")
        openShowFromSubscriptions()
        compose.onNodeWithTag(TestTags.SHOW_SCREEN).assertIsDisplayed()

        tapTab("Home")
        tapTab("Activity")

        // The heart of the rule. Coming back to a tab shows the tab, not whatever you had opened
        // on top of it when you left.
        assertOnTabRoot("Activity")
        compose.onAllNodesWithTag(TestTags.SHOW_SCREEN).assertCountEquals(0)
    }

    @Test
    fun the_segment_you_were_on_survives_leaving_and_returning() {
        launchApp()
        tapTab("Activity")
        tapSegment("Subscriptions")
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").assertIsDisplayed()

        tapTab("Home")
        tapTab("Activity")

        // The other half of the rule: state *within* the tab is kept, so you are not dumped back
        // on Queue every time you glance at Home.
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").assertIsDisplayed()
    }

    @Test
    fun a_detail_screen_opened_from_a_tab_can_still_be_backed_out_of() {
        launchApp()
        tapTab("Activity")
        tapSegment("Subscriptions")
        openShowFromSubscriptions()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()

        assertOnTabRoot("Activity")
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").assertIsDisplayed()
    }

    @Test
    fun tapping_the_tab_you_are_already_on_keeps_you_there() {
        launchApp()
        tapTab("Activity")

        tapTab("Activity")

        assertOnTabRoot("Activity")
    }
}
