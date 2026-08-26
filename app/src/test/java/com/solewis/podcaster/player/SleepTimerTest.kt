package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.FakePlayback
import com.solewis.podcaster.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * The sleep timer, run entirely on virtual time: the timer reads its clock through an injected
 * lambda, so pointing that at `testScheduler` makes `advanceTimeBy` move both the delays and the
 * clock the countdown derives from. A whole hour passes in microseconds and the assertions are
 * exact rather than approximate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val playback = FakePlayback()

    private fun TestScope.timer() =
        SleepTimer(playback, backgroundScope, now = { testScheduler.currentTime })

    /**
     * Moves virtual time on and then runs whatever is due at the new instant.
     *
     * `advanceTimeBy` alone stops *just before* tasks scheduled at exactly the target time, so the
     * countdown tick landing on it has not run yet and every mid-countdown reading comes out one
     * tick stale. That looks like an off-by-one in the timer, which is what it looked like here.
     */
    private fun TestScope.elapse(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun playback_keeps_going_until_the_timer_runs_out() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.start(30 * 60_000L)
        elapse(29 * 60_000L)

        // The failure worth catching is a timer that fires early and cuts an episode off.
        assertThat(playback.pauseCount).isEqualTo(0)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(60_000L))
    }

    @Test
    fun playback_is_paused_when_the_timer_runs_out() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.start(30 * 60_000L)
        elapse(30 * 60_000L + 1)

        assertThat(playback.pauseCount).isEqualTo(1)
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
    }

    @Test
    fun it_pauses_rather_than_toggling() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.start(60_000L)
        elapse(60_001L)

        // Toggling would *resume* playback that had already been paused by hand before the timer
        // expired - the opposite of what a sleep timer is for.
        assertThat(playback.togglePlayPauseCount).isEqualTo(0)
    }

    @Test
    fun the_remaining_time_counts_down_for_the_screen() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.start(10 * 60_000L)
        elapse(90_000L)

        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(8 * 60_000L + 30_000L))
    }

    @Test
    fun cancelling_stops_it_ever_pausing() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()
        timer.start(60_000L)

        timer.cancel()
        elapse(10 * 60_000L)

        assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
        assertThat(playback.pauseCount).isEqualTo(0)
    }

    @Test
    fun extending_adds_to_what_is_left_rather_than_restarting() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()
        timer.start(10 * 60_000L)
        elapse(8 * 60_000L)

        timer.extend(5 * 60_000L)

        // Two minutes were left, so this is seven - not the ten a restart would give.
        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(7 * 60_000L))
    }

    @Test
    fun extending_with_nothing_set_starts_a_timer() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.extend(5 * 60_000L)

        assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(5 * 60_000L))
    }

    @Test
    fun setting_a_new_duration_replaces_the_running_one() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()
        timer.start(60 * 60_000L)

        timer.start(5 * 60_000L)
        elapse(5 * 60_000L + 1)

        // One pause, not two: the first countdown has to be abandoned, not left running alongside.
        assertThat(playback.pauseCount).isEqualTo(1)
    }

    @Test
    fun end_of_episode_never_pauses_on_a_clock() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()

        timer.startEndOfEpisode()
        elapse(3 * 60 * 60_000L)

        // Three hours in and still armed - there is no deadline, only the episode.
        assertThat(timer.state.value).isEqualTo(SleepTimerState.EndOfEpisode)
        assertThat(playback.pauseCount).isEqualTo(0)
    }

    @Test
    fun end_of_episode_stops_the_next_auto_advance_exactly_once() =
        runTest(mainDispatcher.dispatcher) {
            val timer = timer()
            timer.startEndOfEpisode()

            assertThat(timer.consumeEndOfEpisode()).isTrue()
            // Disarmed, so the episode after this one is unaffected - otherwise setting the timer
            // once would silently stop every subsequent episode too.
            assertThat(timer.consumeEndOfEpisode()).isFalse()
            assertThat(timer.state.value).isEqualTo(SleepTimerState.Off)
        }

    @Test
    fun a_running_countdown_does_not_interfere_with_auto_advance() =
        runTest(mainDispatcher.dispatcher) {
            val timer = timer()

            timer.start(30 * 60_000L)

            // A 30 minute timer must not stop the episode boundary it happens to span.
            assertThat(timer.consumeEndOfEpisode()).isFalse()
            assertThat(timer.state.value).isEqualTo(SleepTimerState.Running(30 * 60_000L))
        }

    @Test
    fun switching_from_a_countdown_to_end_of_episode_drops_the_countdown() =
        runTest(mainDispatcher.dispatcher) {
            val timer = timer()
            timer.start(60_000L)

            timer.startEndOfEpisode()
            elapse(10 * 60_000L)

            // The abandoned countdown firing here would cut playback off mid-episode, which is
            // precisely what was just turned off.
            assertThat(playback.pauseCount).isEqualTo(0)
            assertThat(timer.state.value).isEqualTo(SleepTimerState.EndOfEpisode)
        }

    @Test
    fun extending_does_nothing_at_end_of_episode() = runTest(mainDispatcher.dispatcher) {
        val timer = timer()
        timer.startEndOfEpisode()

        timer.extend(5 * 60_000L)

        // There is no deadline to extend; turning it into a five minute countdown would throw away
        // what was actually asked for.
        assertThat(timer.state.value).isEqualTo(SleepTimerState.EndOfEpisode)
    }
}
