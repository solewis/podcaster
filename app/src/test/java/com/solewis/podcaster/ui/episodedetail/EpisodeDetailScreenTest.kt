package com.solewis.podcaster.ui.episodedetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitText
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detail screen's header and its controls.
 *
 * The header is the interesting part: the episode's name is printed once, and which of the two
 * places it appears in depends on where the content has been scrolled to.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeDetailScreenTest {

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

    /**
     * [viewportHeight] exists because this is running on Robolectric, where text has no real font
     * metrics - the 400-sentence description below measures as a single 36px line, so the page fits
     * on screen however much content it is given and never scrolls at all. Constraining the height
     * is what actually produces the overflow the collapsing header responds to.
     */
    private fun launch(title: String = "Patient Zero", viewportHeight: Dp = Dp.Unspecified) {
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(
                episodeRow(
                    podcastId, "1",
                    title = title,
                    durationMillis = 600_000,
                    // Long enough that the screen actually scrolls, which is the whole premise.
                    descriptionHtml = "<p>" + "Show notes. ".repeat(400) + "</p>"
                )
            )
        }
        val viewModel = graph.hosting(
            EpisodeDetailViewModel(
                "$podcastId:1",
                graph.episodeRepository,
                graph.queueRepository,
                graph.playback,
                graph.downloads,
                graph.playbackStarter
            )
        )
        compose.setContent {
            PodcasterTheme {
                Box(
                    modifier = if (viewportHeight == Dp.Unspecified) Modifier
                    else Modifier.height(viewportHeight)
                ) {
                    EpisodeDetailScreen(viewModel = viewModel, onBack = {})
                }
            }
        }
        compose.awaitText(title)
    }

    /**
     * The bar used to be a back button and nothing else, so scrolling into the show notes left a
     * blank strip across the top and no indication of what was being read.
     */
    @Test
    fun the_header_is_untitled_while_the_episodes_own_heading_is_still_on_screen() {
        launch()

        // Exactly one "Patient Zero" on screen - the heading in the content. The header's copy is
        // composed away rather than drawn transparent, so it is genuinely absent here.
        compose.onNodeWithTag(TestTags.screenTitle("Patient Zero")).assertDoesNotExist()
        compose.onNodeWithText("Patient Zero").assertIsDisplayed()
    }

    @Test
    fun the_header_takes_the_title_over_once_the_heading_has_scrolled_away() {
        launch(viewportHeight = 200.dp)

        // Swiped rather than performScrollTo'd on the description: the show notes are the last
        // thing on the screen but they *start* just below the buttons, which is already on screen,
        // so scrolling their top into view moves nothing at all. This actually travels.
        repeat(3) {
            compose.onNodeWithTag(TestTags.EPISODE_DETAIL_SCREEN).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }

        compose.onNodeWithTag(TestTags.screenTitle("Patient Zero")).assertIsDisplayed()
    }

    /**
     * The same three controls, in the same order, as an episode row in a list - reported as the
     * detail screen being the odd one out, with no queue button at all and the download wearing a
     * container the others do not have.
     */
    @Test
    fun play_is_accompanied_by_queue_download_and_the_overflow_menu() {
        launch()

        compose.onNodeWithTag(TestTags.enqueueButton("Patient Zero")).assertExists()
        compose.onNodeWithTag(TestTags.downloadButton(null)).assertExists()
        compose.onNodeWithTag(TestTags.episodeMenu("Patient Zero")).assertExists()
        compose.onNodeWithContentDescription("Add Patient Zero to queue").assertExists()
    }
}
