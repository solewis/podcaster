package com.solewis.podcaster.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.solewis.podcaster.player.PlaybackRestorer
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
 * The bug this covers, end to end at the UI: kill the app mid-episode and reopen it, and the
 * player was simply gone. The position had been saved all along - `PlaybackUiState` was just built
 * exclusively from `ExoPlayer`'s own playlist, which dies with the process.
 *
 * Restoring feeds the same state the player would have, so the mini player has to appear and open
 * Now Playing exactly as it does mid-playback.
 */
@RunWith(AndroidJUnit4::class)
class RestoredPlayerTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
        runBlocking { podcastId = graph.insertShow(title = "Radiolab") }
    }

    @After
    fun tearDown() = graph.close()

    private fun launchApp() {
        val container = graph.appContainer()
        compose.setContent { PodcasterTheme { PodcasterRoot(container = container) } }
    }

    private fun restoreStartupState() = runBlocking {
        PlaybackRestorer(graph.episodeRepository, graph.playback).restore()
    }

    @Test
    fun reopening_mid_episode_brings_the_mini_player_back() {
        runBlocking {
            graph.insertEpisodes(
                episodeRow(podcastId, "1", positionMillis = 90_000, lastPlayedAt = 5_000, durationMillis = 600_000)
            )
        }
        restoreStartupState()

        launchApp()

        compose.awaitTag(TestTags.MINI_PLAYER)
        // Asserted on the bar itself rather than by text alone - the Home list behind it is
        // showing the same episode, so a bare text match would pass with no player on screen.
        compose.onNodeWithTag(TestTags.MINI_PLAYER).assertTextContains("Episode 1")
    }

    @Test
    fun the_restored_mini_player_opens_now_playing_like_any_other() {
        runBlocking {
            graph.insertEpisodes(
                episodeRow(podcastId, "1", positionMillis = 90_000, lastPlayedAt = 5_000, durationMillis = 600_000)
            )
        }
        restoreStartupState()
        launchApp()
        compose.awaitTag(TestTags.MINI_PLAYER)

        compose.onNodeWithTag(TestTags.MINI_PLAYER).performClick()
        compose.waitForIdle()

        // Restored state is not a lesser kind of playback state - the bar is the real one.
        compose.onNodeWithText("Episode 1").assertIsDisplayed()
        compose.onNodeWithTag(TestTags.MINI_PLAYER).assertDoesNotExist()
    }

    @Test
    fun a_library_that_was_never_played_still_opens_with_no_player() {
        runBlocking { graph.insertEpisodes(episodeRow(podcastId, "1")) }
        restoreStartupState()

        launchApp()
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.MINI_PLAYER).assertDoesNotExist()
    }
}
