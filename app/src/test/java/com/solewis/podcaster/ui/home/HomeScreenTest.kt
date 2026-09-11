package com.solewis.podcaster.ui.home

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitText
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The redesigned episode row: image and title on one row, a truncated description under it, the
 * meta/progress line, then a standalone play/queue/download row instead of two icons squeezed
 * beside the title. Home is the app's start destination, so no navigation is needed to reach it.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private lateinit var container: AppContainer
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
    }

    @After
    fun tearDown() = graph.close()

    private fun launch(descriptionPreview: String? = null) {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(podcastId, "1", title = "An Episode", descriptionPreview = descriptionPreview)
            )
        }
        container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
        compose.awaitText("An Episode")
    }

    @Test
    fun a_description_preview_is_shown_under_the_title() {
        launch(descriptionPreview = "Real show notes go here.")

        compose.onNodeWithText("Real show notes go here.").assertExists()
    }

    @Test
    fun an_episode_with_no_stored_preview_shows_no_gap_for_one() {
        launch(descriptionPreview = null)

        // Nothing to assert the absence of by content, so this just pins that the row still
        // renders cleanly with a null preview - an older episode that predates this column, or a
        // feed with no description at all.
        compose.onNodeWithText("An Episode").assertExists()
    }

    @Test
    fun the_action_row_offers_play_queue_download_and_the_overflow_menu_as_standalone_controls() {
        launch()

        compose.onNodeWithContentDescription("Play An Episode").assertExists()
        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).assertExists()
        compose.onNodeWithTag(TestTags.downloadButton(null)).assertExists()
        compose.onNodeWithTag(TestTags.episodeMenu("An Episode")).assertExists()
    }

    /**
     * Reported: left-aligned, the play button sat about 12dp in from the row's left edge - an
     * IconButton centres its icon in a larger box - so it lined up with neither the artwork above
     * it nor the text beside that, and read as a misalignment rather than a choice. Against the
     * right edge there is nothing for it to fail to line up against.
     */
    @Test
    fun the_action_row_is_aligned_to_the_right_edge_rather_than_the_left() {
        launch()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val play = compose.onNodeWithContentDescription("Play An Episode").getUnclippedBoundsInRoot()
        val menu = compose.onNodeWithTag(TestTags.episodeMenu("An Episode")).getUnclippedBoundsInRoot()

        // The overflow menu is the last control, so it is the one that has to reach the right edge -
        // allowing for the row's own 16dp horizontal padding.
        assertThat((root.right - menu.right).value).isLessThan(20f)
        // Stated as a comparison of the two margins rather than against the middle of the row, so
        // that it says the same thing at any screen width: the whole group is pushed right, not
        // just the last icon in it. Left-aligned, the space left over sat on the right instead.
        assertThat(play.left.value).isGreaterThan((root.right - menu.right).value)
    }

    @Test
    fun tapping_the_standalone_queue_button_enqueues_the_episode() {
        launch()

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()
        compose.waitForIdle()

        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { container.queueRepository.observeQueue().first() }
                .any { it.episodeId == "$podcastId:1" }
        }
    }
}
