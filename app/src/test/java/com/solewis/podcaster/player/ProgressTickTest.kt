package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The on-screen clock advancing evenly, at any speed.
 *
 * Reported from a real drive: at 1.75x the timer ran 15:01 through 15:05 quickly, then visibly
 * hung before 15:06, then ran quickly again - a hitch every few seconds. Not a stutter in playback,
 * which was smooth throughout, but aliasing in how the position was sampled: a fixed 500ms wall
 * tick advances 875ms of media at 1.75x, and 875 does not divide 1000, so one displayed second in
 * seven collected two samples and sat there twice as long.
 *
 * The simulation below is the actual evidence - it reproduces the reported symptom against the old
 * fixed tick and shows it gone with the current one.
 */
class ProgressTickTest {

    /**
     * Plays forward for [seconds] of media and returns how long each displayed second was on
     * screen, in wall milliseconds. Even playback means every entry is the same.
     */
    private fun secondsOnScreen(speed: Float, seconds: Int, fixedTickMillis: Long? = null): List<Long> {
        val durations = mutableListOf<Long>()
        var positionMillis = 0L
        var wallSinceChange = 0L
        var shownSecond = 0L
        while (durations.size < seconds) {
            val waitWall = fixedTickMillis
                ?: millisUntilNextDisplayedSecond(positionMillis, speed)
            positionMillis += (waitWall * speed).toLong()
            wallSinceChange += waitWall
            val nowShowing = positionMillis / 1_000L
            if (nowShowing != shownSecond) {
                durations += wallSinceChange
                wallSinceChange = 0
                shownSecond = nowShowing
            }
        }
        return durations
    }

    @Test
    fun the_reported_hitch_is_reproducible_against_the_old_fixed_tick() {
        val onScreen = secondsOnScreen(speed = 1.75f, seconds = 21, fixedTickMillis = 500L)

        // Some seconds took one 500ms tick, others took two - the visible hang before 15:06, and
        // far beyond anything rounding could explain.
        assertThat(onScreen.toSet()).containsExactly(500L, 1_000L)
        assertThat(onScreen.max() - onScreen.min()).isGreaterThan(VISIBLE_UNEVENNESS_MILLIS)
    }

    @Test
    fun every_displayed_second_now_lasts_the_same_wall_time_at_1_75x() {
        val onScreen = secondsOnScreen(speed = 1.75f, seconds = 21)

        // A millisecond of integer rounding is unavoidable and invisible; the old behaviour differed
        // by 500ms, which was the hitch. Asserting a spread rather than equality keeps the test
        // about what a person can actually see.
        assertThat(onScreen.max() - onScreen.min()).isAtMost(VISIBLE_UNEVENNESS_MILLIS)
    }

    @Test
    fun that_holds_at_every_offered_speed() {
        // 1x and 2x always looked fine because their rates divide evenly into a second; the odd
        // ones in between are where this was visible, so all of them are pinned.
        for (speed in listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)) {
            val onScreen = secondsOnScreen(speed, seconds = 21)
            assertThat(onScreen.max() - onScreen.min()).isAtMost(VISIBLE_UNEVENNESS_MILLIS)
        }
    }

    @Test
    fun a_second_of_media_takes_the_wall_time_the_speed_implies() {
        // The clock has to run *fast* at speed, not merely evenly - even and wrong would still be
        // a bug, just a less visible one.
        assertThat(secondsOnScreen(speed = 2f, seconds = 5).first()).isEqualTo(500L)
        // Half speed means a media second takes two wall seconds, not one.
        assertThat(secondsOnScreen(speed = 0.5f, seconds = 5).first()).isEqualTo(2_000L)
    }

    @Test
    fun landing_just_short_of_a_boundary_does_not_spin() {
        // A seek can leave the position a hair before a whole second, making the computed wait
        // tiny. It must not become a busy loop.
        assertThat(millisUntilNextDisplayedSecond(positionMillis = 999, speed = 3f))
            .isAtLeast(50L)
    }

    @Test
    fun a_nonsensical_speed_cannot_stall_the_clock() {
        // Nothing sets zero today, but an unbounded divide would park the display forever.
        assertThat(millisUntilNextDisplayedSecond(positionMillis = 0, speed = 0f))
            .isAtMost(1_000L)
    }

    private companion object {
        /** Well under a frame, and two orders of magnitude below the reported hitch. */
        const val VISIBLE_UNEVENNESS_MILLIS = 10L
    }
}
