package com.solewis.podcaster.ui.common

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.FakePlayback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The projection that stands between the player's clock and a list row.
 *
 * Every assertion here is really about one thing: how many times a playing episode makes the feed
 * recompose. The player publishes a position once per displayed second because the Now Playing
 * screen draws a clock; a row draws whole minutes and a bar a few hundred pixels wide, so most of
 * those emissions were redraws of an identical frame - on every visible row at once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListNowPlayingTest {

    @Test
    fun a_position_change_too_small_for_a_row_to_draw_never_reaches_it() = runTest {
        val playback = FakePlayback()
        val seen = mutableListOf<ListNowPlaying>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            playback.listNowPlaying().toList(seen)
        }

        playback.emitPlaying("ep-1")
        val afterStart = seen.size

        // Four consecutive seconds of the player's clock, all inside one step.
        playback.emitProgress(10_000)
        playback.emitProgress(11_000)
        playback.emitProgress(12_000)
        playback.emitProgress(13_000)
        playback.emitProgress(14_999)

        // One emission for the step, not five. This is the whole point: the four that followed
        // would each have recomposed every visible row to draw the same thing.
        assertThat(seen.size - afterStart).isEqualTo(1)
        assertThat(seen.last().positionMillis).isEqualTo(10_000)

        collector.cancel()
    }

    @Test
    fun crossing_into_the_next_step_does_reach_the_row() = runTest {
        val playback = FakePlayback()
        val seen = mutableListOf<ListNowPlaying>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            playback.listNowPlaying().toList(seen)
        }

        playback.emitPlaying("ep-1")
        playback.emitProgress(10_000)
        playback.emitProgress(15_000)

        // Coarsened, not frozen - a row still follows the episode it is playing.
        assertThat(seen.map { it.positionMillis }).containsAtLeast(10_000L, 15_000L).inOrder()

        collector.cancel()
    }

    @Test
    fun a_paused_episode_is_still_the_active_one() = runTest {
        val playback = FakePlayback()

        playback.emitPaused("ep-1")
        val paused = playback.listNowPlaying().first()

        // Two different questions, and a row needs both: the indicator follows "is this the episode
        // in the player", the play/pause icon follows "is it making sound".
        assertThat(paused.isActive("ep-1")).isTrue()
        assertThat(paused.isPlaying("ep-1")).isFalse()
        assertThat(paused.isActive("ep-2")).isFalse()
    }

    @Test
    fun a_seek_does_not_detach_the_row_it_is_seeking_in() = runTest {
        val playback = FakePlayback()

        playback.emitSeekBuffering("ep-1")
        val seeking = playback.listNowPlaying().first()

        // isPlaying goes false mid-seek while playWhenReady stays true. Following audibility here
        // flicked the icon and dropped the row back to its stored position mid-drag.
        assertThat(seeking.isPlaying("ep-1")).isTrue()
        assertThat(seeking.isActive("ep-1")).isTrue()
    }

    @Test
    fun nothing_loaded_means_no_row_is_active() = runTest {
        val idle = FakePlayback().listNowPlaying().first()

        assertThat(idle.isActive("ep-1")).isFalse()
        assertThat(idle.isPlaying("ep-1")).isFalse()
    }
}

