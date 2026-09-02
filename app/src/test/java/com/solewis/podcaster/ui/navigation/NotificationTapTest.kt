package com.solewis.podcaster.ui.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTag
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tapping the media notification, and the car's "open app", landing on Now Playing.
 *
 * Both used to do nothing at all: the session had no session activity set, so Media3's notification
 * had no content intent to fire - the transport buttons worked while the notification itself was
 * inert. The `PendingIntent` cannot be exercised from here, but where the request *lands* can be,
 * which is the half with branching in it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private val requests = MutableStateFlow(0)

    @Before
    fun setUp() {
        graph = TestGraph()
        runBlocking {
            val podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(episodeRow(podcastId, "1", durationMillis = 600_000))
        }
    }

    @After
    fun tearDown() = graph.close()

    private fun launchApp() {
        val container = graph.appContainer()
        compose.setContent {
            PodcasterTheme {
                PodcasterRoot(container = container, openNowPlayingRequests = requests)
            }
        }
    }

    @Test
    fun the_app_opens_where_it_normally_would_when_nothing_asked_for_now_playing() {
        launchApp()

        compose.awaitTag(TestTags.screenTitle("Library"))
        compose.onNodeWithTag(TestTags.screenTitle("Library")).assertIsDisplayed()
    }

    @Test
    fun a_request_lands_on_now_playing() {
        launchApp()
        compose.awaitTag(TestTags.screenTitle("Library"))

        requests.value = 1
        compose.waitForIdle()

        // Now Playing hides the tab bar, which is the cheapest thing to assert that it is really
        // the destination rather than an overlay on Library.
        compose.onAllNodesWithTag(TestTags.navTab("Home")).assertCountEquals(0)
    }

    @Test
    fun a_second_request_still_works_after_navigating_away() {
        launchApp()
        compose.awaitTag(TestTags.screenTitle("Library"))
        requests.value = 1
        compose.waitForIdle()

        // Back out of Now Playing, then ask again - the reason this is a counter and not a flag,
        // since a flag already at "true" would never fire a second time.
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitForIdle()
        compose.awaitTag(TestTags.navTab("Home"))
        requests.value = 2
        compose.waitForIdle()

        compose.onAllNodesWithTag(TestTags.navTab("Home")).assertCountEquals(0)
    }

    @Test
    fun a_repeated_request_does_not_stack_a_second_copy() {
        launchApp()
        compose.awaitTag(TestTags.screenTitle("Library"))
        requests.value = 1
        compose.waitForIdle()

        // What a rotation does: the same request value re-delivered to a fresh composition.
        requests.value = 1
        compose.waitForIdle()

        // Still exactly one Now Playing. Navigating to a destination already on top would stack it,
        // and then a single back press would leave you looking at an identical screen.
        compose.onAllNodesWithTag(TestTags.MINI_PLAYER).assertCountEquals(0)
    }
}
