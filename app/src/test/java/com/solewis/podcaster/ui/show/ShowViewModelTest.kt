package com.solewis.podcaster.ui.show

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.data.repo.SubscribeResult
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.testing.FeedHost
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
 * The show screen, including the resume pill - the app's headline feature. `JumpTargetResolver`
 * already decides *which* episode to jump to; what's tested here is the layer above it: the pill's
 * wording, and the scroll index, which has to be computed against the sorted list actually on
 * screen rather than the raw one.
 */
@RunWith(AndroidJUnit4::class)
class ShowViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private lateinit var host: FeedHost
    private var podcastId: Long = 0

    @Before
    fun setUp() {
        graph = TestGraph()
        host = FeedHost()
    }

    @After
    fun tearDown() {
        host.close()
        graph.close()
    }

    private fun viewModel(subscriptions: SubscriptionRepository = graph.subscriptionRepository) =
        ShowViewModel(
            podcastId,
            graph.podcastRepository,
            graph.episodeRepository,
            subscriptions,
            graph.queueRepository,
            graph.playback
        )

    private suspend fun TestScope.loadedViewModel(episodeCount: Int = 3): ShowViewModel {
        podcastId = graph.insertShow(title = "Radiolab")
        graph.insertEpisodes(
            *(1..episodeCount).map {
                episodeRow(podcastId, it.toString(), durationMillis = 600_000)
            }.toTypedArray()
        )
        val vm = viewModel()
        keepHot(vm.state)
        vm.state.awaitValue { it.episodes.size == episodeCount }
        return vm
    }

    @Test
    fun episodes_follow_the_shows_sort_order() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        assertThat(vm.state.value.episodes.map { it.title })
            .containsExactly("Episode 3", "Episode 2", "Episode 1").inOrder()

        vm.toggleSortOrder()

        val flipped = vm.state.awaitValue { it.podcast?.sortOrder == SortOrder.OLDEST_FIRST }
        assertThat(flipped.episodes.map { it.title })
            .containsExactly("Episode 1", "Episode 2", "Episode 3").inOrder()
    }

    @Test
    fun trailers_trail_the_list_whichever_way_it_is_sorted() = runTest(mainDispatcher.dispatcher) {
        podcastId = graph.insertShow()
        graph.insertEpisodes(
            episodeRow(podcastId, "1"),
            episodeRow(podcastId, "2"),
            episodeRow(podcastId, "t", chronoIndex = null, title = "Trailer")
        )
        val vm = viewModel()
        keepHot(vm.state)
        vm.state.awaitValue { it.episodes.size == 3 }

        assertThat(vm.state.value.episodes.last().title).isEqualTo("Trailer")

        vm.toggleSortOrder()

        val flipped = vm.state.awaitValue { it.podcast?.sortOrder == SortOrder.OLDEST_FIRST }
        assertThat(flipped.episodes.last().title).isEqualTo("Trailer")
    }

    @Test
    fun no_listening_history_means_no_resume_pill() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        assertThat(vm.state.value.jump).isNull()
    }

    @Test
    fun a_part_listened_episode_offers_to_resume_it_with_time_remaining() =
        runTest(mainDispatcher.dispatcher) {
            val vm = loadedViewModel()
            graph.db.episodeDao().setProgress("$podcastId:2", positionMillis = 180_000, isPlayed = false, now = 5_000)

            val jump = vm.state.awaitValue { it.jump != null }.jump!!
            assertThat(jump.intent).isEqualTo(JumpTargetResolver.Intent.RESUME)
            assertThat(jump.episodeId).isEqualTo("$podcastId:2")
            assertThat(jump.label).isEqualTo("Resume Ep 2")
            assertThat(jump.secondary).isEqualTo("7m left")
        }

    @Test
    fun finishing_an_episode_offers_the_next_one() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        graph.db.episodeDao().setProgress("$podcastId:2", positionMillis = 600_000, isPlayed = true, now = 5_000)

        val jump = vm.state.awaitValue { it.jump != null }.jump!!
        assertThat(jump.intent).isEqualTo(JumpTargetResolver.Intent.NEXT)
        assertThat(jump.label).isEqualTo("Next: Ep 3")
        assertThat(jump.secondary).isEqualTo("10m")
    }

    @Test
    fun being_caught_up_offers_the_last_episode_again() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        (1..3).forEach {
            graph.db.episodeDao().setProgress("$podcastId:$it", positionMillis = 600_000, isPlayed = true, now = it * 1_000L)
        }

        val jump = vm.state.awaitValue { it.jump?.intent == JumpTargetResolver.Intent.REVISIT }.jump!!
        assertThat(jump.label).isEqualTo("Last played: Ep 3")
        assertThat(jump.secondary).isNull()
    }

    @Test
    fun the_pill_scrolls_to_the_targets_place_in_the_sorted_list() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        graph.db.episodeDao().setProgress("$podcastId:1", positionMillis = 60_000, isPlayed = false, now = 5_000)

        // Newest-first, so episode 1 is last on screen even though it is first chronologically.
        // Computing this index against the unsorted list would scroll to the wrong row entirely.
        val newestFirst = vm.state.awaitValue { it.jump != null }.jump!!
        assertThat(newestFirst.itemIndex).isEqualTo(2)

        vm.toggleSortOrder()

        val oldestFirst = vm.state.awaitValue { it.podcast?.sortOrder == SortOrder.OLDEST_FIRST }.jump!!
        assertThat(oldestFirst.itemIndex).isEqualTo(0)
    }

    @Test
    fun an_untitled_episode_falls_back_to_an_ellipsized_title_in_the_pill() =
        runTest(mainDispatcher.dispatcher) {
            podcastId = graph.insertShow()
            graph.insertEpisodes(
                episodeRow(
                    podcastId, "t", chronoIndex = null,
                    title = "A bonus episode with a very long title indeed"
                )
            )
            val vm = viewModel()
            keepHot(vm.state)
            vm.state.awaitValue { it.episodes.isNotEmpty() }
            graph.db.episodeDao().setProgress("$podcastId:t", positionMillis = 1_000, isPlayed = false, now = 5_000)

            val jump = vm.state.awaitValue { it.jump != null }.jump!!
            // Trailers and bonus episodes have no number to show, so the pill borrows the title -
            // capped, because it has to fit on one line above the list.
            assertThat(jump.label).isEqualTo("Resume A bonus episode with a very…")
        }

    @Test
    fun playing_an_episode_passes_the_shows_title_and_artwork() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.play("$podcastId:1")

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        with(graph.playback.played.single()) {
            assertThat(episodeId).isEqualTo("$podcastId:1")
            assertThat(podcastTitle).isEqualTo("Radiolab")
            assertThat(artworkUrl).isEqualTo("https://example.com/show.png")
        }
    }

    @Test
    fun enqueue_adds_to_the_queue() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.enqueue("$podcastId:1")

        awaitTrue("queue row written") { graph.db.queueDao().getAllOrdered().isNotEmpty() }
        assertThat(graph.db.queueDao().getAllOrdered().single().episodeId).isEqualTo("$podcastId:1")
    }

    @Test
    fun a_failed_refresh_surfaces_the_reason_and_stops_the_spinner() = runTest(mainDispatcher.dispatcher) {
        val repository = SubscriptionRepository(graph.db.podcastDao(), graph.db.episodeDao(), FeedFetcher()) { 1_000L }
        host.enqueueFeed("rotating_token_v1.xml")
        podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val vm = viewModel(repository)
        keepHot(vm.state)
        host.enqueueStatus(503)

        vm.refresh()

        assertThat(vm.refreshError.awaitValue { it != null }).contains("503")
        awaitTrue("spinner came down") { !vm.isRefreshing.value }
    }

    @Test
    fun a_second_refresh_while_one_is_running_is_ignored() = runTest(mainDispatcher.dispatcher) {
        val repository = SubscriptionRepository(graph.db.podcastDao(), graph.db.episodeDao(), FeedFetcher()) { 1_000L }
        host.enqueueFeed("rotating_token_v1.xml")
        podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        val vm = viewModel(repository)
        keepHot(vm.state)
        host.enqueueNotModified(delayMillis = 300)

        vm.refresh()
        awaitTrue("the first refresh is in flight") { vm.isRefreshing.value }
        vm.refresh()
        awaitTrue("the first refresh finished") { !vm.isRefreshing.value }

        assertThat(host.requestCount).isEqualTo(2)
    }

    @Test
    fun unsubscribing_signals_the_screen_to_leave() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.unsubscribe()

        // There is nothing left to render once the row and its episodes are gone.
        assertThat(vm.didUnsubscribe.awaitValue { it }).isTrue()
    }
}
