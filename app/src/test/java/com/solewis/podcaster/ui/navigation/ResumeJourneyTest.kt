package com.solewis.podcaster.ui.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTag
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
 * The feature the app exists for, driven through the real UI: a show with hundreds of episodes and
 * one you are partway through, and getting back to it in a single tap.
 *
 * This covers the surface - the pill's wording, that it appears only when the episode is out of
 * sight, that tapping it reaches the episode, and that the episode's own screen offers to resume
 * rather than restart. The mechanism underneath it (a real player writing positions back to the
 * database) is on the device, in `ProgressWriterIntegrationTest`, since it needs a real ExoPlayer.
 */
@RunWith(AndroidJUnit4::class)
class ResumeJourneyTest {

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

    /** [partListened] is the episode number left half-finished, if any. */
    private fun seed(episodeCount: Int, partListened: Int?, positionMillis: Long = 180_000) {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                *(1..episodeCount).map {
                    episodeRow(podcastId, it.toString(), durationMillis = 600_000)
                }.toTypedArray()
            )
            partListened?.let {
                graph.db.episodeDao().setProgress(
                    "$podcastId:$it", positionMillis = positionMillis, isPlayed = false, now = 5_000
                )
            }
        }
    }

    private fun openShow() {
        val container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
        compose.onNodeWithTag(TestTags.navTab("Activity")).performClick()
        compose.onNodeWithTag(TestTags.segment("Subscriptions")).performClick()
        compose.awaitText("Radiolab")
        compose.onNodeWithText("Radiolab").performClick()
        compose.waitForIdle()
    }

    @Test
    fun a_show_you_are_partway_through_offers_to_resume_it() {
        // Newest-first, so episode 2 sits near the bottom of a 40-episode list - far out of sight,
        // which is the whole reason the pill exists.
        seed(episodeCount = 40, partListened = 2)

        openShow()

        compose.awaitTag(TestTags.RESUME_PILL)
        compose.onNodeWithTag(TestTags.RESUME_PILL).assertIsDisplayed()
        compose.onNodeWithText("Resume Ep 2").assertIsDisplayed()
        compose.onNodeWithText("7m left").assertIsDisplayed()
    }

    @Test
    fun tapping_the_pill_reaches_the_episode() {
        seed(episodeCount = 40, partListened = 2)
        openShow()

        compose.awaitTag(TestTags.RESUME_PILL)
        compose.onNodeWithTag(TestTags.RESUME_PILL).performClick()
        compose.waitForIdle()

        // Scrolled far enough down the list that the target row is now on screen.
        compose.onNodeWithText("Episode 2").assertIsDisplayed()
    }

    @Test
    fun the_episodes_own_screen_offers_to_resume_rather_than_restart() {
        seed(episodeCount = 40, partListened = 2)
        openShow()
        compose.awaitTag(TestTags.RESUME_PILL)
        compose.onNodeWithTag(TestTags.RESUME_PILL).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Episode 2").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.EPISODE_DETAIL_SCREEN).assertIsDisplayed()
        // "Resume", not "Play" - the button has to admit you are already partway in.
        compose.onNodeWithText("Resume").assertIsDisplayed()
        compose.onNodeWithText("7m left").assertIsDisplayed()
    }

    @Test
    fun a_show_you_have_never_played_offers_no_pill() {
        seed(episodeCount = 40, partListened = null)

        openShow()

        compose.onAllNodesWithTag(TestTags.RESUME_PILL).assertCountEquals(0)
    }

    @Test
    fun the_pill_stays_hidden_while_the_episode_is_already_in_view() {
        // The newest episode is the first row, so with newest-first ordering it is on screen from
        // the moment the list opens. A pill pointing at a row you can already see is pure clutter.
        seed(episodeCount = 40, partListened = 40)

        openShow()

        compose.awaitText("Episode 40")
        compose.onNodeWithText("Episode 40").assertIsDisplayed()
        compose.onAllNodesWithTag(TestTags.RESUME_PILL).assertCountEquals(0)
    }

    @Test
    fun finishing_an_episode_turns_the_pill_into_the_next_one() {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                *(1..40).map { episodeRow(podcastId, it.toString(), durationMillis = 600_000) }.toTypedArray()
            )
            graph.db.episodeDao().setProgress("$podcastId:2", positionMillis = 600_000, isPlayed = true, now = 5_000)
        }

        openShow()

        // Having finished episode 2, the useful next tap is episode 3 - not replaying what you
        // just heard.
        compose.awaitText("Next: Ep 3")
        compose.onNodeWithText("Next: Ep 3").assertIsDisplayed()
    }
}
