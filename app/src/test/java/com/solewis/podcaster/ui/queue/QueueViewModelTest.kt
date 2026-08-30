package com.solewis.podcaster.ui.queue

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.TestGraph
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.episodeRow
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueViewModelTest {

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

    private suspend fun TestScope.loadedViewModel(queued: Int): QueueViewModel {
        podcastId = graph.insertShow()
        graph.insertEpisodes(*(1..queued).map { episodeRow(podcastId, it.toString()) }.toTypedArray())
        (1..queued).forEach { graph.queueRepository.enqueue("$podcastId:$it") }

        val vm = graph.hosting(QueueViewModel(graph.queueRepository, graph.playback, graph.playbackStarter))
        keepHot(vm.items)
        vm.items.awaitValue { it.size == queued }
        return vm
    }

    @Test
    fun the_queue_is_listed_in_play_order() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(queued = 3)

        assertThat(vm.items.value.map { it.title })
            .containsExactly("Episode 1", "Episode 2", "Episode 3").inOrder()
    }

    @Test
    fun moving_an_item_up_reorders_the_visible_list() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(queued = 3)

        vm.moveUp(vm.items.value[2].queueId)

        assertThat(vm.items.awaitValue { it.first().title == "Episode 1" && it[1].title == "Episode 3" })
            .isNotEmpty()
    }

    @Test
    fun moving_an_item_down_reorders_the_visible_list() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(queued = 3)

        vm.moveDown(vm.items.value[0].queueId)

        vm.items.awaitValue { it.map { item -> item.title } == listOf("Episode 2", "Episode 1", "Episode 3") }
    }

    @Test
    fun removing_an_item_drops_it_from_the_list() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(queued = 2)

        vm.remove(vm.items.value.first().queueId)

        vm.items.awaitValue { it.map { item -> item.title } == listOf("Episode 2") }
    }

    @Test
    fun playing_an_item_now_takes_it_out_of_the_queue_rather_than_leaving_it_queued() =
        runTest(mainDispatcher.dispatcher) {
            val vm = loadedViewModel(queued = 2)

            vm.playNow(vm.items.value.first())

            awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
            assertThat(graph.playback.played.single().episodeId).isEqualTo("$podcastId:1")
            // Otherwise it would play now and then play again when its turn came round.
            vm.items.awaitValue { it.map { item -> item.title } == listOf("Episode 2") }
        }

    @Test
    fun playing_an_item_now_resumes_it_at_its_saved_position() = runTest(mainDispatcher.dispatcher) {
        val vm = loadedViewModel(queued = 1)
        graph.db.episodeDao().setProgress("$podcastId:1", positionMillis = 75_000, isPlayed = false, now = 1L)

        vm.playNow(vm.items.value.first())

        awaitTrue("episode handed to playback") { graph.playback.played.isNotEmpty() }
        assertThat(graph.playback.played.single().startPositionMillis).isEqualTo(75_000)
    }
}
