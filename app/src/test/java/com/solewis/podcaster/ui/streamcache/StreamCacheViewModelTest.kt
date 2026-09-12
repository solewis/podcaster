package com.solewis.podcaster.ui.streamcache

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.FakeStreamCache
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.episodeRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What specifically is filling up the streaming cache - joined against real episode metadata, so
 * a person can tell what to delete without memorising ids.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StreamCacheViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val graph = TestGraph()
    private val streamCache = FakeStreamCache()

    @After
    fun tearDown() = graph.close()

    private fun viewModel() = StreamCacheViewModel(graph.episodeRepository, streamCache)

    @Test
    fun a_cached_episode_is_shown_with_its_real_title() = runTest(mainDispatcher.dispatcher) {
        val podcastId = graph.insertShow(title = "Radiolab")
        graph.insertEpisodes(episodeRow(podcastId, key = "1", title = "An Episode"))
        streamCache.seed("$podcastId:1", sizeBytes = 10_000_000)

        val viewModel = viewModel()

        awaitTrue("the cached episode is joined with its title") {
            viewModel.rows.value.singleOrNull()?.episode?.title == "An Episode"
        }
        assertThat(viewModel.rows.value.single().episode?.podcastTitle).isEqualTo("Radiolab")
    }

    @Test
    fun a_cache_entry_for_an_unsubscribed_show_is_shown_rather_than_hidden() =
        runTest(mainDispatcher.dispatcher) {
            // Exactly the stray entry this screen exists to surface - dropping it would hide the
            // one thing someone opened the screen to find and delete.
            streamCache.seed("no-such-episode", sizeBytes = 5_000_000)

            val viewModel = viewModel()

            awaitTrue("the orphaned entry still appears") { viewModel.rows.value.size == 1 }
            assertThat(viewModel.rows.value.single().episode).isNull()
        }

    @Test
    fun rows_are_sorted_biggest_first() = runTest(mainDispatcher.dispatcher) {
        val podcastId = graph.insertShow()
        graph.insertEpisodes(
            episodeRow(podcastId, key = "1", title = "Small"),
            episodeRow(podcastId, key = "2", title = "Big")
        )
        streamCache.seed("$podcastId:1", sizeBytes = 1_000)
        streamCache.seed("$podcastId:2", sizeBytes = 9_000)

        val viewModel = viewModel()

        awaitTrue("both rows are in") { viewModel.rows.value.size == 2 }
        assertThat(viewModel.rows.value.map { it.episode?.title }).containsExactly("Big", "Small").inOrder()
    }

    @Test
    fun the_total_is_the_sum_of_every_entry() = runTest(mainDispatcher.dispatcher) {
        streamCache.seed("a", sizeBytes = 1_000)
        streamCache.seed("b", sizeBytes = 2_000)

        val viewModel = viewModel()

        awaitTrue("the total reflects both entries") { viewModel.totalBytes.value == 3_000L }
    }

    @Test
    fun removing_one_entry_leaves_the_rest() = runTest(mainDispatcher.dispatcher) {
        streamCache.seed("a", sizeBytes = 1_000)
        streamCache.seed("b", sizeBytes = 2_000)
        val viewModel = viewModel()
        awaitTrue("both entries loaded") { viewModel.rows.value.size == 2 }

        viewModel.remove("a")

        awaitTrue("only the other one is left") {
            viewModel.rows.value.singleOrNull()?.entry?.episodeId == "b"
        }
        assertThat(streamCache.removed).containsExactly("a")
    }

    @Test
    fun clearing_all_empties_the_list() = runTest(mainDispatcher.dispatcher) {
        streamCache.seed("a", sizeBytes = 1_000)
        val viewModel = viewModel()
        awaitTrue("the entry loaded") { viewModel.rows.value.isNotEmpty() }

        viewModel.clearAll()

        awaitTrue("everything is gone") { viewModel.rows.value.isEmpty() }
        assertThat(streamCache.cleared).isTrue()
    }
}
