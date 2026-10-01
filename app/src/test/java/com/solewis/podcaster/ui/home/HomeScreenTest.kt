package com.solewis.podcaster.ui.home

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

    /** Two rows, so an assertion can tell "marks the right row" from "marks every row". */
    private fun launchWithTwoEpisodes() {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(podcastId, "1", title = "An Episode", pubDateMillis = 2_000),
                episodeRow(podcastId, "2", title = "Another Episode", pubDateMillis = 1_000)
            )
        }
        container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
        compose.awaitText("An Episode")
        compose.awaitText("Another Episode")
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
     * Play is held apart from the rest of the controls - alone against the right edge, with queue,
     * download and the overflow menu gathered at the left. As one more bare icon in a line of four
     * it carried the same weight as "add to queue", which is not what the row is for.
     */
    @Test
    fun play_sits_alone_at_the_right_with_the_other_controls_gathered_left() {
        launch()

        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val play = compose.onNodeWithContentDescription("Play An Episode").getUnclippedBoundsInRoot()
        val queue = compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).getUnclippedBoundsInRoot()
        val menu = compose.onNodeWithTag(TestTags.episodeMenu("An Episode")).getUnclippedBoundsInRoot()

        // Play reaches the right edge, allowing for the row's own 16dp horizontal padding.
        assertThat((root.right - play.right).value).isLessThan(20f)
        // The secondary controls start at the left edge, by the same margin.
        assertThat(queue.left.value).isLessThan(20f)
        // And there is real space between the two groups, which is what separates play rather than
        // the fill alone - shoulder to shoulder it would read as a toolbar with one coloured item.
        assertThat((play.left - menu.right).value).isGreaterThan(24f)
    }

    @Test
    fun tapping_the_standalone_queue_button_enqueues_the_episode() {
        launch()

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()

        // Waits on the screen, not by polling the database from inside waitUntil: that blocked the
        // main thread on a Room query each time round, and failed intermittently in full-suite
        // runs - the one test in this class that waited that way, and the only one that did.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Remove An Episode from queue", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(runBlocking { container.queueRepository.observeQueue().first() }.map { it.episodeId })
            .containsExactly("$podcastId:1")
    }

    /**
     * Shows in these tests are stamped as just refreshed by the test clock. Against the real clock
     * they all looked stale, so every launch sent a live request to the made-up feed URL - network
     * in a unit test, finishing at whatever moment the network chose.
     */
    @Test
    fun opening_the_app_does_not_refresh_a_show_that_was_just_fetched() {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val client = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain -> requests.incrementAndGet(); chain.proceed(chain.request()) }
            .build()
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(episodeRow(podcastId, "1", title = "An Episode"))
        }
        container = graph.appContainer(httpClient = client)
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
        compose.awaitText("An Episode")
        compose.waitForIdle()
        Thread.sleep(500)

        assertThat(requests.get()).isEqualTo(0)
    }


    /**
     * The rail itself is a `drawBehind` with no layout node, so there is nothing in the tree to
     * find - see [com.solewis.podcaster.ui.common.nowPlayingRail] for why it is a draw rather than
     * a composable. This asserts the fact it stands for; that it actually paints is covered on
     * device by NowPlayingRailTest, which can read pixels.
     */
    @Test
    fun the_playing_row_says_so_for_a_screen_reader() {
        launch()
        graph.playback.emitPlaying("$podcastId:1")
        compose.waitForIdle()

        compose.onNode(hasStateDescription("Now playing"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun a_paused_row_is_still_marked_as_the_active_one() {
        launch()
        graph.playback.emitPaused("$podcastId:1")
        compose.waitForIdle()

        compose.onNode(hasStateDescription("Paused here"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun a_row_the_player_has_never_touched_is_not_marked() {
        launch()

        compose.onAllNodes(hasStateDescription("Now playing"), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodes(hasStateDescription("Paused here"), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun only_the_active_row_is_marked_when_another_episode_is_playing() {
        launchWithTwoEpisodes()
        graph.playback.emitPlaying("$podcastId:1")
        compose.waitForIdle()

        // Exactly one, not one per row - the marker is driven by the episode id, so a bug handing
        // every row the player's state would show up here and nowhere else.
        compose.onAllNodes(hasStateDescription("Now playing"), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    /**
     * Reported: adding to the queue showed nothing, so there was no way to tell it had worked. The
     * button now reads queued, says so out loud, and the app confirms each change.
     */
    @Test
    fun the_queue_button_shows_and_toggles_the_queued_state() {
        launch()

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Remove An Episode from queue", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Added to queue").assertExists()

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("Add An Episode to queue", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(runBlocking { container.queueRepository.observeQueue().first() }).isEmpty()
    }

    /**
     * Reported: removing straight after adding left "Added to queue" up until it timed out, with the
     * removal's message stuck behind it. The bar has to describe the latest action, at once.
     */
    @Test
    fun a_new_queue_message_replaces_the_one_showing_instead_of_waiting_behind_it() {
        launch()

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Added to queue").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(TestTags.enqueueButton("An Episode")).performClick()

        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithText("Removed from queue").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Added to queue").assertDoesNotExist()
    }
}
