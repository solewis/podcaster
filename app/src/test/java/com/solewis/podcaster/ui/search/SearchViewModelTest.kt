package com.solewis.podcaster.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.remote.ItunesSearchApi
import com.solewis.podcaster.data.repo.PodcastSearchResult
import com.solewis.podcaster.data.repo.SearchRepository
import com.solewis.podcaster.testing.FeedHost
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.keepHot
import com.solewis.podcaster.testing.settle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Search debounces keystrokes and reads its Subscribed/Subscribe state live from Room rather than
 * tracking it locally, so both of those are what's pinned here. The debounce runs on virtual time;
 * everything downstream of it (real HTTP, real Room) is awaited in real time.
 */
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private lateinit var graph: TestGraph
    private lateinit var host: FeedHost
    private lateinit var searchHost: FeedHost

    @Before
    fun setUp() {
        graph = TestGraph()
        host = FeedHost()
        searchHost = FeedHost()
    }

    @After
    fun tearDown() {
        searchHost.close()
        host.close()
        graph.close()
    }

    private fun viewModel() = SearchViewModel(
        SearchRepository(ItunesSearchApi(httpClient = searchHost.hostRedirectingClient())),
        graph.subscriptionRepository,
        graph.podcastRepository
    )

    private fun oneResult(title: String, feedUrl: String) =
        """{"results":[{"collectionId":7,"collectionName":"$title","feedUrl":"$feedUrl"}]}"""

    @Test
    fun typing_searches_once_the_keystrokes_stop() = runTest(mainDispatcher.dispatcher) {
        searchHost.enqueueBody(oneResult("Acquired", "https://feeds.example/acquired"))
        val vm = viewModel()

        vm.onQueryChange("acq")
        advanceTimeBy(DEBOUNCE_MILLIS + 1)

        val state = vm.state.awaitValue { it.results.isNotEmpty() }
        assertThat(state.results.single().title).isEqualTo("Acquired")
        assertThat(state.isSearching).isFalse()
    }

    @Test
    fun rapid_typing_only_searches_for_the_final_query() = runTest(mainDispatcher.dispatcher) {
        searchHost.enqueueBody(oneResult("Acquired", "https://feeds.example/acquired"))
        val vm = viewModel()

        vm.onQueryChange("a")
        advanceTimeBy(100)
        vm.onQueryChange("ac")
        advanceTimeBy(100)
        vm.onQueryChange("acq")
        advanceTimeBy(DEBOUNCE_MILLIS + 1)

        vm.state.awaitValue { it.results.isNotEmpty() }
        settle()
        // One request, not three - the whole point of the debounce, and the difference between
        // one iTunes call per search and one per keystroke (which is how you get rate limited).
        assertThat(searchHost.requestCount).isEqualTo(1)
    }

    @Test
    fun clearing_the_query_drops_the_results_without_searching() = runTest(mainDispatcher.dispatcher) {
        searchHost.enqueueBody(oneResult("Acquired", "https://feeds.example/acquired"))
        val vm = viewModel()
        vm.onQueryChange("acq")
        advanceTimeBy(DEBOUNCE_MILLIS + 1)
        vm.state.awaitValue { it.results.isNotEmpty() }

        vm.onQueryChange("")
        advanceTimeBy(DEBOUNCE_MILLIS + 1)

        val state = vm.state.awaitValue { it.results.isEmpty() }
        assertThat(state.isSearching).isFalse()
        assertThat(searchHost.requestCount).isEqualTo(1)
    }

    @Test
    fun a_failing_search_surfaces_an_error_rather_than_hanging_on_the_spinner() =
        runTest(mainDispatcher.dispatcher) {
            searchHost.enqueueStatus(429)
            val vm = viewModel()

            vm.onQueryChange("acq")
            advanceTimeBy(DEBOUNCE_MILLIS + 1)

            val state = vm.state.awaitValue { it.error != null }
            assertThat(state.isSearching).isFalse()
            assertThat(state.error).contains("Rate limited")
        }

    @Test
    fun already_subscribed_shows_are_marked_from_the_database() = runTest(mainDispatcher.dispatcher) {
        val id = graph.db.podcastDao().insert(
            com.solewis.podcaster.testing.podcastRow(title = "Acquired", feedUrl = "https://feeds.example/acquired")
        )
        val vm = viewModel()
        keepHot(vm.state)

        // Driven by Room, not local bookkeeping, so a subscription made on another screen shows
        // up here too.
        val state = vm.state.awaitValue { it.subscribedFeedUrls.isNotEmpty() }
        assertThat(state.subscribedFeedUrls).containsEntry("https://feeds.example/acquired", id)
    }

    @Test
    fun subscribing_from_a_result_marks_it_subscribed_and_clears_the_spinner() =
        runTest(mainDispatcher.dispatcher) {
            host.enqueueFeed("rotating_token_v1.xml")
            val feedUrl = host.feedUrl()
            val vm = viewModel()
            keepHot(vm.state)

            vm.subscribe(PodcastSearchResult(7, "Rotating Token Show", null, feedUrl, null, null))

            // Two independent updates land here in either order - Room's subscribed-urls
            // collector and the subscribe call's own completion - so wait for both to settle.
            val state = vm.state.awaitValue {
                it.subscribedFeedUrls.containsKey(feedUrl) && it.subscribingFeedUrl == null
            }
            assertThat(state.error).isNull()
        }

    @Test
    fun a_failed_subscribe_reports_why_and_stops_the_spinner() = runTest(mainDispatcher.dispatcher) {
        host.enqueueStatus(500)
        val vm = viewModel()
        keepHot(vm.state)

        vm.subscribe(PodcastSearchResult(7, "Broken", null, host.feedUrl(), null, null))

        val state = vm.state.awaitValue { it.error != null }
        assertThat(state.subscribingFeedUrl).isNull()
        assertThat(state.error).contains("500")
    }

    @Test
    fun unsubscribing_from_a_result_removes_the_show() = runTest(mainDispatcher.dispatcher) {
        val feedUrl = "https://feeds.example/acquired"
        graph.db.podcastDao().insert(
            com.solewis.podcaster.testing.podcastRow(title = "Acquired", feedUrl = feedUrl)
        )
        val vm = viewModel()
        keepHot(vm.state)
        vm.state.awaitValue { it.subscribedFeedUrls.containsKey(feedUrl) }

        vm.unsubscribe(feedUrl)

        assertThat(vm.state.awaitValue { it.subscribedFeedUrls.isEmpty() }.subscribedFeedUrls).isEmpty()
    }

    @Test
    fun unsubscribing_something_never_subscribed_is_a_no_op() = runTest(mainDispatcher.dispatcher) {
        val vm = viewModel()
        keepHot(vm.state)

        vm.unsubscribe("https://not-subscribed.example/feed.xml")

        awaitTrue("no crash and nothing subscribed") { vm.state.value.subscribedFeedUrls.isEmpty() }
    }

    private companion object {
        /** Mirrors SearchViewModel's own debounce window. */
        const val DEBOUNCE_MILLIS = 400L
    }
}
