package com.solewis.podcaster.ui.show

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
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

    private fun openShow(
        positionMillis: Long = 0,
        isPlayed: Boolean = false,
        descriptionPreview: String? = null,
        pubDateMillis: Long? = null,
        lastPlayedAt: Long? = null,
        /** Extra filler rows, for the tests that need the list to be longer than the screen. */
        extraEpisodes: Int = 0,
        /**
         * Which episode number Patient Zero takes. Defaults to the first, which under newest-first
         * puts it at the very bottom of the list - fine for reading a row, wrong for anything about
         * scrolling *to* it, since a list clamped at its end ignores where you asked the row to go.
         */
        episodeKey: String = "1"
    ) {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(
                    podcastId, episodeKey, title = "Patient Zero",
                    durationMillis = 51 * 60_000L, positionMillis = positionMillis, isPlayed = isPlayed,
                    descriptionPreview = descriptionPreview, pubDateMillis = pubDateMillis,
                    lastPlayedAt = lastPlayedAt
                ),
                *(1..(extraEpisodes + 1))
                    .filter { it.toString() != episodeKey }
                    .map { episodeRow(podcastId, "$it") }
                    .toTypedArray()
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

    /**
     * Brings the first episode row into view. Necessary since the whole page became one lazy list:
     * the show header scrolls with everything else, which means the first row starts below the fold
     * and a LazyColumn has not composed it at all - so it cannot be found, let alone asserted on.
     */
    private fun scrollToFirstEpisode() {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Patient Zero", substring = true))
        compose.waitForIdle()
    }

    @Test
    fun a_description_preview_is_shown_under_the_title() {
        openShow(descriptionPreview = "Real show notes go here.")
        scrollToFirstEpisode()

        compose.onNodeWithText("Real show notes go here.").assertExists()
    }

    @Test
    fun an_untouched_episode_states_its_length_once() {
        openShow()
        scrollToFirstEpisode()

        // Exactly one, counted on the unmerged tree: the row is clickable, so the merged tree
        // collapses its Texts into a single node whose text is a concatenation, and counting there
        // would report 1 however many times the label actually appears.
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun a_part_listened_episode_states_time_remaining_once_and_not_its_length() {
        openShow(positionMillis = 10 * 60_000L)
        scrollToFirstEpisode()

        compose.onAllNodesWithText("41m left", substring = true, useUnmergedTree = true).assertCountEquals(1)
        // The full length is not the useful number once you are partway in, so it should be gone
        // rather than sitting alongside the remaining time.
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun a_finished_episode_says_finished_once_and_drops_the_timings() {
        openShow(positionMillis = 51 * 60_000L, isPlayed = true)
        scrollToFirstEpisode()

        // Once, and inline in the metadata line. It used to say "Played" and to be accompanied by
        // a separate tick icon adrift in the trailing controls, which read as a third button.
        compose.onAllNodesWithText("Finished", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("51m", substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun the_finished_tick_is_not_a_control_of_its_own() {
        openShow(positionMillis = 51 * 60_000L, isPlayed = true)
        scrollToFirstEpisode()

        // The tick used to be a separately-labelled icon below the play button and the overflow
        // menu, floating between them with nothing to attach it to - it read as a third thing to
        // press. Inline after the word, it is punctuation, and it carries no label of its own
        // because the word already says it (a screen reader would otherwise say "Finished,
        // played").
        compose.onAllNodesWithContentDescription("Played", useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Finished", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    /**
     * "Episode 1", spelled out and alone on the line where the Home feed puts the show's name, in
     * the same weight and accent colour - the two lists had drifted into describing the same
     * episode differently. The date it used to share this line with now sits in the metadata line
     * below the description, which is where Home has always carried it.
     */
    @Test
    fun the_header_names_the_episode_number_in_full_and_leaves_the_date_to_the_meta_line() {
        openShow(pubDateMillis = 1_756_000_000_000L)
        scrollToFirstEpisode()

        compose.onAllNodesWithText("Episode 1", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Ep 1", useUnmergedTree = true).assertCountEquals(0)

        // Unmerged throughout: the row is clickable, so the merged tree collapses every Text in it
        // into one node whose bounds are the whole row - the trap the tests above document.
        val date = compose.onAllNodesWithText("Aug", substring = true, useUnmergedTree = true)
        date.assertCountEquals(1)
        // Below the description, not up in the header beside the episode number.
        assertThat(date[0].getUnclippedBoundsInRoot().top.value)
            .isGreaterThan(
                compose.onAllNodesWithText("Episode 1", useUnmergedTree = true)[0]
                    .getUnclippedBoundsInRoot().bottom.value
            )
    }

    /**
     * The description and the metadata line used to be indented past the artwork, so this list
     * started them at a different place than the Home feed did, and the row lost the single left
     * edge its title, artwork and controls all otherwise share.
     */
    @Test
    fun the_description_and_meta_line_share_the_rows_left_edge_with_the_artwork() {
        openShow(descriptionPreview = "Real show notes go here.")
        scrollToFirstEpisode()

        val titleLeft = compose.onAllNodesWithText("Patient Zero", useUnmergedTree = true)[0]
            .getUnclippedBoundsInRoot().left
        val descriptionLeft =
            compose.onAllNodesWithText("Real show notes go here.", useUnmergedTree = true)[0]
                .getUnclippedBoundsInRoot().left

        // The title is the indented one - it sits beside the artwork - so the description starting
        // to the left of it is exactly what "flush with the artwork" looks like from here.
        assertThat(descriptionLeft.value).isLessThan(titleLeft.value)
    }

    /**
     * The show's name, artwork and subscribe row used to be pinned above the list, spending a third
     * of a phone screen on things you read once. They scroll now, and the bar picks the name up on
     * the way past - so the name is in exactly one of the two places at any time.
     */
    @Test
    fun the_header_takes_the_show_name_over_once_it_has_scrolled_away() {
        openShow()

        // Not in the bar to begin with: the header's own copy is on screen.
        compose.onNodeWithTag(TestTags.screenTitle("Radiolab")).assertDoesNotExist()

        compose.onNodeWithTag(TestTags.SHOW_SCREEN).performTouchInput { swipeUp() }
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.screenTitle("Radiolab")).assertIsDisplayed()
    }

    /**
     * The tab row is the one thing that does not scroll away, so there is always a way back to
     * About - and, once the episodes are long past, something saying what you are looking at.
     */
    @Test
    fun the_tab_row_stays_put_when_the_header_scrolls_away() {
        // Long enough that the swipes below actually carry the tab row's own position off the top
        // of the screen. With one episode the page barely scrolls, and the tabs stay visible
        // whether they are pinned or not - the test passed either way and proved nothing.
        openShow(extraEpisodes = 30)

        repeat(6) {
            compose.onNodeWithTag(TestTags.SHOW_SCREEN).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }

        compose.onNodeWithText("Episodes").assertIsDisplayed()
        compose.onNodeWithText("About").assertIsDisplayed()
        // The header went with the scroll - it is not pinned alongside the tabs.
        compose.onNodeWithTag(TestTags.SHOW_MENU).assertDoesNotExist()
    }

    /**
     * Reported: tapping the resume pill landed the episode it jumped to underneath the tab row,
     * clipped along its top edge.
     *
     * The old code scrolled the row flush to the top of the list and then nudged it down by a fixed
     * 80px. That was tuned when the tab row was fixed furniture *above* the list; now it is pinned
     * *inside* it, overlaying the top, and a constant cannot know how tall it is.
     */
    @Test
    fun jumping_to_the_last_listened_episode_clears_the_pinned_tab_row() {
        // lastPlayedAt, not just a position: JumpTargetResolver picks the episode with the greatest
        // lastPlayedAt and ignores position entirely, so without it there is no target and no pill.
        // Mid-list on purpose - see openShow's episodeKey. At the bottom the list clamps at its
        // end and lands the row clear of the tabs whatever clearance is asked for, so the test
        // passed with the fix reverted.
        openShow(
            positionMillis = 10 * 60_000L,
            lastPlayedAt = 5_000L,
            extraEpisodes = 30,
            episodeKey = "16"
        )
        compose.awaitText("Radiolab")

        // No scrolling to set this up: under newest-first the target is the *last* row, so it is
        // already off screen and the pill is already offering to jump to it. Swiping down here
        // would scroll toward the target and hide the pill, which is what the pill is for.
        compose.onNodeWithTag(TestTags.RESUME_PILL).performClick()
        compose.waitForIdle()

        val tabsBottom = compose.onNodeWithText("Episodes").getUnclippedBoundsInRoot().bottom
        val target = compose.onAllNodesWithText("Patient Zero", useUnmergedTree = true)[0]
            .getUnclippedBoundsInRoot()

        // Wholly below the tab row, not tucked under it. Its own top edge is what was being eaten.
        assertThat(target.top.value).isAtLeast(tabsBottom.value)
    }

    @Test
    fun the_row_for_the_playing_episode_offers_pause_rather_than_play() {
        // Reported: an episode started from this list kept showing a play arrow, so the row that
        // was making sound looked unplayed and its button could not stop it. These rows had no
        // pause state at all - the Home feed's have always had one, and these were missed.
        openShow()
        scrollToFirstEpisode()

        graph.playback.emitPlaying("$podcastId:1")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Pause Patient Zero", useUnmergedTree = true)
            .assertExists()
        compose.onAllNodesWithContentDescription("Play Patient Zero", useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun that_pause_button_stops_playback_rather_than_restarting_the_episode() {
        openShow()
        scrollToFirstEpisode()
        graph.playback.emitPlaying("$podcastId:1")
        compose.waitForIdle()

        // The merged node, which is the IconButton carrying the click. Injecting on the unmerged
        // Icon inside it does not reach the handler - the same trap `clickEpisodeRow` documents.
        compose.onNodeWithContentDescription("Pause Patient Zero").performScrollTo().performClick()
        compose.waitForIdle()

        // The old button always called play(), which restarted the episode from its stored
        // position - the opposite of what a pause icon promises.
        assertThat(graph.playback.togglePlayPauseCount).isEqualTo(1)
        assertThat(graph.playback.played).isEmpty()
    }

    @Test
    fun a_row_that_is_not_playing_still_offers_play() {
        openShow()
        scrollToFirstEpisode()

        graph.playback.emitPlaying("$podcastId:something-else")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Play Patient Zero", useUnmergedTree = true)
            .assertExists()
    }
}
