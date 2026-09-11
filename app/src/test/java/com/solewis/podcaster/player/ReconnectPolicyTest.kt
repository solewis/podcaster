package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The retry schedule itself, independent of any player or network - see [PlaybackErrorRetrier] for
 * what actually calls `prepare()` on the strength of these numbers.
 */
class ReconnectPolicyTest {

    @Test
    fun the_first_few_attempts_come_quickly() {
        val policy = ReconnectPolicy()

        // A lot of real drops - a tunnel, a brief dead zone - clear within seconds, and the first
        // attempt should not make someone wait through a long, unhurried interval to find that out.
        assertThat(policy.delayBeforeAttempt(1)).isLessThan(10_000L)
    }

    @Test
    fun attempts_back_off_rather_than_arriving_at_a_fixed_rate() {
        val policy = ReconnectPolicy()

        val first = policy.delayBeforeAttempt(1)
        val second = policy.delayBeforeAttempt(2)
        val third = policy.delayBeforeAttempt(3)

        assertThat(second).isGreaterThan(first)
        assertThat(third).isGreaterThan(second)
    }

    @Test
    fun after_the_early_attempts_it_settles_into_a_steady_interval_rather_than_growing_forever() {
        val policy = ReconnectPolicy()

        // Unbounded exponential backoff would eventually mean waiting minutes between tries, which
        // is indistinguishable from having given up while technically still retrying.
        val late = policy.delayBeforeAttempt(50)
        val laterStill = policy.delayBeforeAttempt(51)

        assertThat(late).isEqualTo(laterStill)
    }

    @Test
    fun it_is_still_worth_trying_well_within_the_ceiling() {
        val policy = ReconnectPolicy(giveUpAfterMillis = 60_000)

        assertThat(policy.shouldKeepTrying(elapsedMillis = 1_000)).isTrue()
    }

    @Test
    fun it_gives_up_once_the_ceiling_is_reached() {
        // Not literally forever - see ReconnectPolicy's own doc for why an infinite silent retry
        // is its own failure mode, distinct from a working retry.
        val policy = ReconnectPolicy(giveUpAfterMillis = 60_000)

        assertThat(policy.shouldKeepTrying(elapsedMillis = 60_000)).isFalse()
        assertThat(policy.shouldKeepTrying(elapsedMillis = 120_000)).isFalse()
    }

    @Test
    fun the_default_ceiling_is_a_few_minutes_not_a_few_seconds_or_forever() {
        val policy = ReconnectPolicy()

        // Long enough to survive a real dead zone; short enough that "it just silently stopped"
        // never lasts an entire commute unremarked. Pinned as a range rather than an exact figure,
        // since the precise number is a product choice and not what this test is protecting.
        assertThat(policy.giveUpAfterMillis).isAtLeast(60_000L)
        assertThat(policy.giveUpAfterMillis).isAtMost(10 * 60_000L)
    }
}
