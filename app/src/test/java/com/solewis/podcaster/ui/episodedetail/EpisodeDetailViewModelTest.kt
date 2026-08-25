package com.solewis.podcaster.ui.episodedetail

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detail screen's play button changes meaning with context - Play, Resume, Pause, Play again -
 * and its progress bar has to track the player rather than the database while this episode is the
 * one playing. Both are decided here.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeDetailViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0
    private val episodeId get() = "$podcastId:1"

    @Before
    fun setUp() {
        graph = TestGraph()
    }

    @After
    fun tearDown() = graph.close()

    private suspend fun TestScope.loadedViewModel(
        positionMillis: Long = 0,
        isPlayed: Boolean = false
    ): EpisodeDetailViewModel {
        podcastId = graph.insertShow(title = "Radiolab")
        graph.insertEpisodes(
            episodeRow(podcastId, "1", positionMillis = positionMillis, isPlayed = isPlayed, durationMillis = 600_000),
            episodeRow(podcastId, "2")
        )
        val vm = EpisodeDetailViewModel(episodeId, graph.episodeRepository, graph.queueRepository, graph.playback)
        keepHot(vm.state)
        vm.state.awaitValue { it.episode != null }
        return vm
    }

    @Test
    fun the_episode_loads_with_its_show_context() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        val episode = vm.state.value.episode!!
        assertThat(episode.title).isEqualTo("Episode 1")
        assertThat(episode.podcastTitle).isEqualTo("Radiolab")
        assertThat(vm.state.value.isLoading).isFalse()
    }

    @Test
    fun another_episode_playing_does_not_make_this_one_look_active() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        graph.playback.emitPlaying("$podcastId:2")
        graph.playback.emitProgress(positionMillis = 55_000, durationMillis = 600_000)

        val state = vm.state.awaitValue { it.episode != null }
        assertThat(state.isPlayingThis).isFalse()
        // Critically the live position must stay null, or this episode's bar would animate along
        // with a completely different episode's playback.
        assertThat(state.livePositionMillis).isNull()
        assertThat(state.liveDurationMillis).isNull()
    }

    @Test
    fun this_episode_playing_surfaces_the_live_position() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        graph.playback.emitPlaying(episodeId)
        graph.playback.emitProgress(positionMillis = 120_000, durationMillis = 600_000)

        val state = vm.state.awaitValue { it.livePositionMillis == 120_000L }
        assertThat(state.isPlayingThis).isTrue()
        assertThat(state.liveDurationMillis).isEqualTo(600_000)
    }

    @Test
    fun paused_on_this_episode_keeps_the_position_but_is_not_playing() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        graph.playback.emitPlaying(episodeId)
        vm.state.awaitValue { it.isPlayingThis }

        graph.playback.emitPaused(episodeId)

        val state = vm.state.awaitValue { !it.isPlayingThis }
        // Still the loaded episode, so the bar keeps showing where you are - it just isn't moving.
        assertThat(state.livePositionMillis).isNotNull()
    }

    @Test
    fun starting_an_unplayed_episode_begins_at_zero() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.togglePlay()

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        assertThat(graph.playback.played.single().startPositionMillis).isEqualTo(0)
    }

    @Test
    fun starting_a_part_listened_episode_resumes_it() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(positionMillis = 200_000)

        vm.togglePlay()

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        assertThat(graph.playback.played.single().startPositionMillis).isEqualTo(200_000)
    }

    @Test
    fun a_finished_episode_plays_again_from_the_start() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(positionMillis = 590_000, isPlayed = true)

        vm.togglePlay()

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        assertThat(graph.playback.played.single().startPositionMillis).isEqualTo(0)
    }

    @Test
    fun tapping_the_button_while_this_episode_plays_pauses_instead_of_restarting() =
        runTest(mainDispatcher.dispatcher) {
            val vm = loadedViewModel(positionMillis = 200_000)
            graph.playback.emitPlaying(episodeId)
            vm.state.awaitValue { it.isPlayingThis }

            vm.togglePlay()

            awaitTrue("playback toggled") { graph.playback.togglePlayPauseCount == 1 }
            // Restarting the episode you are already listening to would be the worst possible
            // outcome of tapping what looks like a pause button.
            assertThat(graph.playback.played).isEmpty()
        }

    @Test
    fun tapping_the_button_while_a_different_episode_plays_switches_to_this_one() =
        runTest(mainDispatcher.dispatcher) {
            val vm = loadedViewModel()
            graph.playback.emitPlaying("$podcastId:2")
            vm.state.awaitValue { it.episode != null }

            vm.togglePlay()

            awaitTrue("this episode handed to playback") { graph.playback.played.isNotEmpty() }
            assertThat(graph.playback.played.single().episodeId).isEqualTo(episodeId)
            assertThat(graph.playback.togglePlayPauseCount).isEqualTo(0)
        }

    @Test
    fun enqueue_adds_this_episode_to_the_queue() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.enqueue()

        awaitTrue("queue row written") { graph.db.queueDao().getAllOrdered().isNotEmpty() }
        assertThat(graph.db.queueDao().getAllOrdered().single().episodeId).isEqualTo(episodeId)
    }
}
