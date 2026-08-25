package com.solewis.podcaster.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.episodeRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup restore - the reason the app no longer reopens with an empty player after being killed.
 * The position was always written to Room; nothing ever read it back.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRestorerTest {

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() = runTest {
        graph = TestGraph()
        podcastId = graph.insertShow(title = "Radiolab")
    }

    @After
    fun tearDown() = graph.close()

    private fun restorer() = PlaybackRestorer(graph.episodeRepository, graph.playback)

    @Test
    fun the_last_played_episode_comes_back_paused_at_its_saved_position() = runTest {
        graph.insertEpisodes(
            episodeRow(podcastId, "1", positionMillis = 90_000, lastPlayedAt = 5_000, durationMillis = 600_000)
        )

        restorer().restore()

        with(graph.playback.state.value) {
            assertThat(episodeId).isEqualTo("$podcastId:1")
            assertThat(title).isEqualTo("Episode 1")
            assertThat(podcastTitle).isEqualTo("Radiolab")
            assertThat(isPlaying).isFalse()
        }
        assertThat(graph.playback.progress.value.positionMillis).isEqualTo(90_000)
        // Carried from the feed so a restored mini player can draw a real progress bar before any
        // player has loaded the media and reported a duration of its own.
        assertThat(graph.playback.progress.value.durationMillis).isEqualTo(600_000)
    }

    @Test
    fun a_library_that_has_never_been_played_restores_nothing() = runTest {
        graph.insertEpisodes(episodeRow(podcastId, "1"), episodeRow(podcastId, "2"))

        restorer().restore()

        assertThat(graph.playback.restored).isEmpty()
        assertThat(graph.playback.state.value.episodeId).isNull()
    }

    @Test
    fun an_episode_started_while_the_database_was_being_read_is_not_replaced() = runTest {
        graph.insertEpisodes(episodeRow(podcastId, "1", positionMillis = 90_000, lastPlayedAt = 5_000))
        // Startup races the first frame: by the time the read comes back the user may already have
        // tapped something. Restoring over that would swap the episode out from under them.
        graph.playback.emitPlaying("$podcastId:2")

        restorer().restore()

        assertThat(graph.playback.restored).isEmpty()
        assertThat(graph.playback.state.value.episodeId).isEqualTo("$podcastId:2")
    }
}
