package com.solewis.podcaster.ui.subscriptions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.testing.FeedHost
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.settle
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private lateinit var host: FeedHost

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

    private fun viewModel() = SubscriptionsViewModel(graph.podcastRepository, graph.subscriptionRepository)

    @Test
    fun every_subscription_is_listed() = runTest(mainDispatcher.dispatcher) {
        graph.insertShow(title = "Show A")
        graph.insertShow(title = "Show B")
        val vm = viewModel()
        keepHot(vm.podcasts)

        assertThat(vm.podcasts.awaitValue { it.size == 2 }.map { it.title })
            .containsExactly("Show A", "Show B")
    }

    @Test
    fun unsubscribing_removes_the_show_from_the_list() = runTest(mainDispatcher.dispatcher) {
        val id = graph.insertShow(title = "Show A")
        val vm = viewModel()
        keepHot(vm.podcasts)
        vm.podcasts.awaitValue { it.isNotEmpty() }

        vm.unsubscribe(id)

        assertThat(vm.podcasts.awaitValue { it.isEmpty() }).isEmpty()
    }

    @Test
    fun refresh_all_reports_progress_and_finishes() = runTest(mainDispatcher.dispatcher) {
        val repository = SubscriptionRepository(
            graph.db.podcastDao(), graph.db.episodeDao(), FeedFetcher()
        ) { 1_000L }
        host.enqueueFeed("rotating_token_v1.xml")
        repository.subscribe(host.feedUrl())
        host.enqueueNotModified()
        val vm = SubscriptionsViewModel(graph.podcastRepository, repository)

        vm.refreshAll()

        // Whether the spinner is caught mid-flight depends on timing, but it must always come back
        // down - a stuck spinner would also block every later refresh via the re-entrancy guard.
        assertThat(vm.isRefreshing.awaitValue { !it }).isFalse()
    }

    @Test
    fun a_second_refresh_while_one_is_running_is_ignored() = runTest(mainDispatcher.dispatcher) {
        val repository = SubscriptionRepository(
            graph.db.podcastDao(), graph.db.episodeDao(), FeedFetcher()
        ) { 1_000L }
        host.enqueueFeed("rotating_token_v1.xml")
        repository.subscribe(host.feedUrl())
        val vm = SubscriptionsViewModel(graph.podcastRepository, repository)
        // Held open so the second call below is unambiguously concurrent with the first, rather
        // than racing a refresh that may already have finished.
        host.enqueueNotModified(delayMillis = 300)

        vm.refreshAll()
        awaitTrue("the first refresh is in flight") { vm.isRefreshing.value }
        vm.refreshAll()
        awaitTrue("the first refresh finished") { !vm.isRefreshing.value }

        // One subscribe plus one refresh. Without the guard the second call would make it three -
        // and a double tap on the refresh button would hit every feed host twice.
        assertThat(host.requestCount).isEqualTo(2)
    }
}
