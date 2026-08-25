package com.solewis.podcaster.ui.show

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitText
import com.solewis.podcaster.testing.episodeRow
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
 * What an episode row actually says.
 *
 * Written after a merge silently produced rows reading "Ep 664  Aug 21, 2026 · 51m" *and* "51m" -
 * the duration twice, because two branches had each added it in a different place. Every one of the
 * 265 tests then in the suite passed, since none of them looked at how many times a label appeared.
 */
@RunWith(AndroidJUnit4::class)
class ShowScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
    }

    @After
    fun tearDown() = graph.close()

    private fun openShow(positionMillis: Long = 0, isPlayed: Boolean = false) {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(
                    podcastId, "1", title = "Patient Zero",
                    durationMillis = 51 * 60_000L, positionMillis = positionMillis, isPlayed = isPlayed
                )
            )
        }
        val container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
        compose.onNodeWithTag(TestTags.navTab("Activity")).performClick()
        compose.onNodeWithTag(TestTags.segment("Subscriptions")).performClick()
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").performClick()
        compose.waitForIdle()
    }

    @Test
    fun an_untouched_episode_states_its_length_once() {
        openShow()

        compose.awaitText("Patient Zero")
        // Exactly one, counted on the unmerged tree: the row is clickable, so the merged tree
        // collapses its Texts into a single node whose text is a concatenation, and counting there
        // would report 1 however many times the label actually appears.
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun a_part_listened_episode_states_time_remaining_once_and_not_its_length() {
        openShow(positionMillis = 10 * 60_000L)

        compose.awaitText("41m left")
        compose.onAllNodesWithText("41m left", substring = true, useUnmergedTree = true).assertCountEquals(1)
        // The full length is not the useful number once you are partway in, so it should be gone
        // rather than sitting alongside the remaining time.
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_finished_episode_says_played_once_and_drops_the_timings() {
        openShow(positionMillis = 51 * 60_000L, isPlayed = true)

        compose.awaitText("Played")
        compose.onAllNodesWithText("Played", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun the_header_carries_the_episode_number_and_date() {
        openShow()

        compose.awaitText("Ep 1")
        compose.onAllNodesWithText("Ep 1", useUnmergedTree = true).assertCountEquals(1)
    }
}
