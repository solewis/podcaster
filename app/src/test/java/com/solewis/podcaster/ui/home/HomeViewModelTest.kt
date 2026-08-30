package com.solewis.podcaster.ui.home

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.keepHot
import com.solewis.podcaster.testing.settle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home's own logic, most of it about the per-row loading spinner. That spinner has already shipped
 * broken once - pausing an episode put it back on a row that had long since started playing - so
 * the lifecycle of `loadingEpisodeId` is the bulk of what's pinned here.
 */
@RunWith(AndroidJUnit4::class)
class HomeViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
    }

    @After
    fun tearDown() = graph.close()

    private fun viewModel() = graph.hosting(
        HomeViewModel(
            graph.podcastRepository,
            graph.episodeRepository,
            graph.queueRepository,
            graph.playback,
            graph.downloads,
            graph.playbackStarter
        )
    )

    /** Builds the ViewModel, keeps its state hot, and waits for Room's first emission. */
    private suspend fun TestScope.loadedViewModel(): HomeViewModel {
        val vm = viewModel()
        keepHot(vm.state)
        vm.state.awaitValue { !it.isLoading }
        return vm
    }

    private suspend fun seedShowWithEpisodes() {
        podcastId = graph.insertShow(title = "Radiolab")
        graph.insertEpisodes(
            episodeRow(podcastId, "1", pubDateMillis = 1_000),
            episodeRow(podcastId, "2", pubDateMillis = 5_000)
        )
    }

    private fun feedItem(key: String) = EpisodeFeedItem(
        id = "$podcastId:$key",
        podcastId = podcastId,
        podcastTitle = "Radiolab",
        podcastArtworkUrl = "https://example.com/show.png",
        title = "Episode $key",
        pubDateMillis = null,
        durationMillis = null,
        displayNumber = null,
        episodeType = "full",
        artworkUrl = null,
        positionMillis = 0,
        isPlayed = false,
        lastPlayedAt = null
    )

    @Test
    fun the_feed_lists_every_episode_across_shows_newest_first() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        assertThat(vm.state.value.episodes.map { it.title }).containsExactly("Episode 2", "Episode 1").inOrder()
        assertThat(vm.state.value.subscriptions.map { it.title }).containsExactly("Radiolab")
    }

    @Test
    fun loading_stays_true_until_the_first_database_emission_arrives() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        // Before anything is collected the state is still the initial one. Distinguishing this from
        // "no subscriptions" is what stops the empty-state message flashing on every launch.
        assertThat(vm.state.value.isLoading).isTrue()

        keepHot(vm.state)
        assertThat(vm.state.awaitValue { !it.isLoading }.isLoading).isFalse()
    }

    @Test
    fun tapping_play_shows_a_spinner_while_nothing_is_audible_yet() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        vm.play(feedItem("1"))

        val state = vm.state.awaitValue { it.loadingEpisodeId != null }
        assertThat(state.loadingEpisodeId).isEqualTo("$podcastId:1")
        // Nothing is playing yet - covering exactly this gap (controller connection, then
        // buffering) is the spinner's whole job, and a play button alone gives no sign of it.
        assertThat(state.nowPlayingEpisodeId).isNull()
    }

    @Test
    fun playing_a_row_asks_for_that_episode_with_its_show_context() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        vm.play(feedItem("2"))
        awaitTrue("episode 2 handed to playback") { graph.playback.played.isNotEmpty() }

        assertThat(graph.playback.played).hasSize(1)
        with(graph.playback.played.single()) {
            assertThat(episodeId).isEqualTo("$podcastId:2")
            assertThat(podcastTitle).isEqualTo("Radiolab")
            assertThat(artworkUrl).isEqualTo("https://example.com/show.png")
        }
    }

    @Test
    fun the_spinner_clears_once_that_episode_is_actually_audible() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()
        vm.play(feedItem("1"))
        awaitTrue("episode 1 handed to playback") { graph.playback.played.isNotEmpty() }

        graph.playback.emitPlaying("$podcastId:1")

        assertThat(vm.state.awaitValue { it.loadingEpisodeId == null }.loadingEpisodeId).isNull()
    }

    @Test
    fun pausing_does_not_bring_the_spinner_back() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()
        vm.play(feedItem("1"))
        awaitTrue("episode 1 handed to playback") { graph.playback.played.isNotEmpty() }
        graph.playback.emitPlaying("$podcastId:1")
        vm.state.awaitValue { it.loadingEpisodeId == null }

        graph.playback.emitPaused("$podcastId:1")
        vm.state.awaitValue { it.nowPlayingEpisodeId == null }
        settle()

        // The shipped bug: the pending id was only masked while isPlaying held, so pausing
        // un-masked a stale id and put a spinner on an episode that had started minutes ago.
        assertThat(vm.state.value.loadingEpisodeId).isNull()
    }

    @Test
    fun the_spinner_clears_when_some_other_episode_takes_over_instead() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()
        vm.play(feedItem("1"))
        awaitTrue("episode 1 handed to playback") { graph.playback.played.isNotEmpty() }

        graph.playback.emitPlaying("$podcastId:2")

        // Otherwise a tap that never produced sound leaves a spinner turning forever.
        assertThat(vm.state.awaitValue { it.loadingEpisodeId == null }.loadingEpisodeId).isNull()
    }

    @Test
    fun the_spinner_clears_when_the_episode_cannot_be_resolved_at_all() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        vm.play(feedItem("does-not-exist"))

        assertThat(vm.state.awaitValue { it.loadingEpisodeId == null }.loadingEpisodeId).isNull()
        assertThat(graph.playback.played).isEmpty()
    }

    @Test
    fun a_row_is_marked_now_playing_only_while_it_is_making_sound() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        graph.playback.emitPlaying("$podcastId:1")
        assertThat(vm.state.awaitValue { it.nowPlayingEpisodeId != null }.nowPlayingEpisodeId)
            .isEqualTo("$podcastId:1")

        graph.playback.emitPaused("$podcastId:1")
        assertThat(vm.state.awaitValue { it.nowPlayingEpisodeId == null }.nowPlayingEpisodeId).isNull()
    }

    @Test
    fun live_position_and_duration_are_surfaced_for_the_playing_row() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        graph.playback.emitProgress(positionMillis = 30_000, durationMillis = 600_000)

        val loaded = vm.state.awaitValue { it.nowPlayingPositionMillis == 30_000L }
        assertThat(loaded.nowPlayingDurationMillis).isEqualTo(600_000)
    }

    @Test
    fun enqueue_adds_the_episode_to_the_queue() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = viewModel()

        vm.enqueue(feedItem("1"))

        awaitTrue("queue row written") { graph.db.queueDao().getAllOrdered().isNotEmpty() }
        assertThat(graph.db.queueDao().getAllOrdered().map { it.episodeId })
            .containsExactly("$podcastId:1")
    }

    @Test
    fun the_mini_transport_toggles_playback() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()

        vm.togglePlayPause()

        awaitTrue("toggle reached playback") { graph.playback.togglePlayPauseCount == 1 }
    }

    @Test
    fun an_episode_can_be_downloaded_from_the_home_feed() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()

        vm.download("$podcastId:1")

        // Downloading from Home was blocked until the row's trailing actions moved into a menu -
        // there was no room for a fourth 48dp button.
        awaitTrue("download requested") { graph.downloads.requested == listOf("$podcastId:1") }
    }

    @Test
    fun an_episode_can_be_marked_played_from_the_home_feed() = runTest(mainDispatcher.dispatcher) {
        seedShowWithEpisodes()
        val vm = loadedViewModel()
        val episode = vm.state.awaitValue { it.episodes.isNotEmpty() }
            .episodes.first { it.id == "$podcastId:1" }

        vm.togglePlayed(episode)

        awaitTrue("marked played") {
            graph.db.episodeDao().getById("$podcastId:1")?.isPlayed == true
        }
    }
}
