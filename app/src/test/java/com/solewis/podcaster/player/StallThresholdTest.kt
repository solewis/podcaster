package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Deciding that a wait is long enough to show a spinner.
 *
 * The rule exists because a seek *always* waits on data briefly - 100-250ms in the good case,
 * measured on device against the real player. A spinner bound to the raw buffering flag would flash
 * on every drag of the scrubber, which is the same flicker that binding the play/pause icon to
 * `isPlaying` used to cause, wearing a different glyph.
 *
 * On virtual time rather than against a real player. The first version of this test did drive a
 * real one, and it passed alone and failed in the suite - because the seek genuinely took longer
 * than the threshold there. That made it a measurement of how fast the emulator felt rather than a
 * test of the rule. The device is the right place to measure what a seek costs; this is the right
 * place to check what is done with that number.
 */
class StallThresholdTest {

    private fun waiting(isBuffering: Boolean = true, playWhenReady: Boolean = true) =
        PlaybackUiState(isBuffering = isBuffering, playWhenReady = playWhenReady)

    private fun settled() = PlaybackUiState(isBuffering = false, playWhenReady = true, isPlaying = true)

    @Test
    fun a_wait_shorter_than_the_threshold_is_never_called_a_stall() = runTest {
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        // A scrub: buffering, then audio again well inside the threshold.
        states.value = waiting()
        advanceTimeBy(250)
        states.value = settled()
        advanceTimeBy(2_000)

        assertThat(seen).doesNotContain(true)
        job.cancel()
    }

    @Test
    fun a_wait_that_goes_on_is_reported_once_the_threshold_passes() = runTest {
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        states.value = waiting()
        advanceTimeBy(STALL_VISIBLE_AFTER_MILLIS - 1)
        assertThat(seen).doesNotContain(true)

        advanceTimeBy(2)
        assertThat(seen).contains(true)
        job.cancel()
    }

    @Test
    fun the_stall_clears_when_the_audio_comes_back() = runTest {
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        states.value = waiting()
        advanceTimeBy(STALL_VISIBLE_AFTER_MILLIS + 1)
        assertThat(seen.last()).isTrue()

        states.value = settled()
        advanceTimeBy(10)

        assertThat(seen.last()).isFalse()
        job.cancel()
    }

    @Test
    fun a_run_of_short_waits_never_adds_up_to_a_stall() = runTest {
        // Dragging the scrubber is many seeks in a row, not one. Each wait has to be judged on its
        // own, or a series of harmless rebuffers would eventually trip the indicator.
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        repeat(10) {
            states.value = waiting()
            advanceTimeBy(200)
            states.value = settled()
            advanceTimeBy(200)
        }

        assertThat(seen).doesNotContain(true)
        job.cancel()
    }

    @Test
    fun silence_that_is_not_buffering_is_not_a_stall() = runTest {
        // Playback suppressed rather than starved - a phone call, say. Nothing is loading, so a
        // loading indicator would be a lie.
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        states.value = PlaybackUiState(isBuffering = false, playWhenReady = true, isPlaying = false)
        advanceTimeBy(5_000)

        assertThat(seen).doesNotContain(true)
        job.cancel()
    }

    @Test
    fun buffering_while_paused_is_not_a_stall_either() = runTest {
        // Nobody is waiting for audio if nobody asked for audio.
        val states = MutableStateFlow(settled())
        val seen = mutableListOf<Boolean>()
        val job = launch { states.stalledAfterWaiting().toList(seen) }

        states.value = waiting(playWhenReady = false)
        advanceTimeBy(5_000)

        assertThat(seen).doesNotContain(true)
        job.cancel()
    }
}
