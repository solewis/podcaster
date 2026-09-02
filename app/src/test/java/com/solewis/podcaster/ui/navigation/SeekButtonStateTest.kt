package com.solewis.podcaster.ui.navigation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTag
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
 * The play/pause control holding still while you scrub.
 *
 * Reported from the phone: dragging the scrubber forward made the play/pause button flick to the
 * other state and back. A seek drops a playing `ExoPlayer` into `STATE_BUFFERING`, so `isPlaying`
 * goes false for that moment and true again once it has rebuffered - confirmed on device - while
 * `playWhenReady` stays true throughout. The buttons were bound to `isPlaying`, so they reported
 * playback as having stopped and restarted itself on every scrub.
 *
 * This was invisible to the whole UI suite before, and not because nobody wrote the test:
 * [com.solewis.podcaster.testing.FakePlayback] could only express the two steady states, so
 * "playing but momentarily silent" was not a thing any test could set up. The fake now models it,
 * which is what makes this test possible at all.
 */
@RunWith(AndroidJUnit4::class)
class SeekButtonStateTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0
    private val episodeId get() = "$podcastId:1"

    @Before
    fun setUp() {
        graph = TestGraph()
        runBlocking {
            podcastId = graph.insertShow(title = "Radiolab")
            graph.insertEpisodes(episodeRow(podcastId, "1", durationMillis = 600_000))
        }
    }

    @After
    fun tearDown() = graph.close()

    private fun launchApp() {
        val container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
    }

    private fun pauseButtons() =
        compose.onAllNodesWithContentDescription("Pause", useUnmergedTree = true)

    private fun playButtons() =
        compose.onAllNodesWithContentDescription("Play", useUnmergedTree = true)

    @Test
    fun the_mini_player_keeps_showing_pause_while_a_seek_rebuffers() {
        launchApp()
        compose.awaitTag(TestTags.navTab("Home"))
        graph.playback.emitPlaying(episodeId)
        compose.waitForIdle()
        pauseButtons().assertCountEquals(1)

        graph.playback.emitSeekBuffering(episodeId)
        compose.waitForIdle()

        // Still one pause control, and no play control anywhere - the flicker was this pair
        // swapping over and back.
        pauseButtons().assertCountEquals(1)
        playButtons().assertCountEquals(0)
    }

    @Test
    fun the_now_playing_control_keeps_showing_pause_while_a_seek_rebuffers() {
        launchApp()
        compose.awaitTag(TestTags.navTab("Home"))
        graph.playback.emitPlaying(episodeId)
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.MINI_PLAYER).performClick()
        compose.waitForIdle()
        pauseButtons().assertCountEquals(1)

        graph.playback.emitSeekBuffering(episodeId)
        compose.waitForIdle()

        pauseButtons().assertCountEquals(1)
        playButtons().assertCountEquals(0)
    }

    @Test
    fun a_real_pause_still_swaps_the_control_over() {
        launchApp()
        compose.awaitTag(TestTags.navTab("Home"))
        graph.playback.emitPlaying(episodeId)
        compose.waitForIdle()

        graph.playback.emitPaused(episodeId)
        compose.waitForIdle()

        // The other half of the fix: drawing intent must not make the button ignore an actual
        // pause, which would be a worse bug than the flicker.
        playButtons().assertCountEquals(1)
        pauseButtons().assertCountEquals(0)
    }
}
