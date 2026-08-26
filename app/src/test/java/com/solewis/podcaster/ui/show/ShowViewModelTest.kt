package com.solewis.podcaster.ui.show

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.repo.DownloadStatus
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
import com.solewis.podcaster.testing.settle
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
        graph.hosting(
            ShowViewModel(
                podcastId,
                graph.podcastRepository,
                graph.episodeRepository,
                subscriptions,
                graph.queueRepository,
                graph.playback,
                graph.downloads
            )
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

    /** A host-backed subscription, so requests to it can be counted. */
    private suspend fun subscribedToHost(): SubscriptionRepository {
        val repository =
            SubscriptionRepository(graph.db.podcastDao(), graph.db.episodeDao(), FeedFetcher()) { graph.clock }
        host.enqueueFeed("rotating_token_v1.xml")
        podcastId = (repository.subscribe(host.feedUrl()) as SubscribeResult.Success).podcastId
        return repository
    }

    @Test
    fun opening_a_show_checks_for_new_episodes_without_being_asked() =
        runTest(mainDispatcher.dispatcher) {
            val repository = subscribedToHost()
            graph.clock += SubscriptionRepository.STALE_AFTER_MILLIS + 1
            host.enqueueNotModified()

            viewModel(repository)

            // The whole point of opening a show is to see what is in it now. Before this, the
            // answer was whatever had last been fetched until you found the refresh button.
            awaitTrue("the show was checked on open") { host.requestCount == 2 }
        }

    @Test
    fun reopening_a_show_just_checked_makes_no_request() = runTest(mainDispatcher.dispatcher) {
        val repository = subscribedToHost()

        viewModel(repository)

        settle()
        assertThat(host.requestCount).isEqualTo(1)
    }

    @Test
    fun the_automatic_check_stays_invisible_even_when_the_feed_is_down() =
        runTest(mainDispatcher.dispatcher) {
            val repository = subscribedToHost()
            graph.clock += SubscriptionRepository.STALE_AFTER_MILLIS + 1
            host.enqueueStatus(503)

            val vm = viewModel(repository)

            awaitTrue("the check happened") { host.requestCount == 2 }
            settle()
            // Nobody asked for this one, so a snackbar over a screen that opened fine would be
            // noise - and sharing the spinner flag would let it swallow a real tap on refresh.
            assertThat(vm.refreshError.value).isNull()
            assertThat(vm.isRefreshing.value).isFalse()
        }

    @Test
    fun downloading_from_a_row_asks_for_that_episode() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.download("$podcastId:2")

        awaitTrue("download requested") { graph.downloads.requested == listOf("$podcastId:2") }
    }

    @Test
    fun a_rows_download_state_reaches_the_screen() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        keepHot(vm.downloadStates)

        graph.downloads.emit("$podcastId:2", DownloadStatus.DOWNLOADING, percent = 30f)

        // Keyed by episode id, because the row that draws the progress ring has to be the row for
        // the episode actually downloading.
        val states = vm.downloadStates.awaitValue { it.isNotEmpty() }
        assertThat(states.keys).containsExactly("$podcastId:2")
        assertThat(states.getValue("$podcastId:2").percent).isEqualTo(30f)
    }

    @Test
    fun marking_the_show_played_clears_the_backlog_and_says_how_many() =
        runTest(mainDispatcher.dispatcher) {
            val vm = loadedViewModel()
            graph.db.episodeDao().setProgress("$podcastId:1", 600_000, isPlayed = true, now = 5_000)

            vm.markAllPlayed()

            // Two of the three, since episode 1 was already finished - the count goes on screen.
            assertThat(vm.markedAllPlayed.awaitValue { it != null }).isEqualTo(2)
            assertThat(vm.state.awaitValue { s -> s.episodes.all { it.isPlayed } }.episodes).hasSize(3)
        }

    @Test
    fun the_confirmation_is_cleared_once_shown() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()
        vm.markAllPlayed()
        vm.markedAllPlayed.awaitValue { it != null }

        vm.clearMarkedAllPlayed()

        // Otherwise the snackbar returns on every recomposition of the screen.
        assertThat(vm.markedAllPlayed.value).isNull()
    }

    @Test
    fun unsubscribing_signals_the_screen_to_leave() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel()

        vm.unsubscribe()

        // There is nothing left to render once the row and its episodes are gone.
        assertThat(vm.didUnsubscribe.awaitValue { it }).isTrue()
    }
}
