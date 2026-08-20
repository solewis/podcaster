package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompletionRuleTest {

    @Test
    fun `well before the end is not complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 60_000, durationMillis = 600_000)).isFalse()
    }

    @Test
    fun `within the threshold of the end is complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 585_000, durationMillis = 600_000)).isTrue()
    }

    @Test
    fun `exactly at the threshold boundary is complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 580_000, durationMillis = 600_000)).isTrue()
    }

    @Test
    fun `one millisecond before the threshold boundary is not complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 579_999, durationMillis = 600_000)).isFalse()
    }

    @Test
    fun `position at or past duration is complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 600_000, durationMillis = 600_000)).isTrue()
        assertThat(CompletionRule.isComplete(positionMillis = 650_000, durationMillis = 600_000)).isTrue()
    }

    @Test
    fun `unknown duration is never complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 999_999, durationMillis = null)).isFalse()
    }

    @Test
    fun `zero or negative duration is never complete`() {
        assertThat(CompletionRule.isComplete(positionMillis = 0, durationMillis = 0)).isFalse()
        assertThat(CompletionRule.isComplete(positionMillis = 0, durationMillis = -1)).isFalse()
    }

    @Test
    fun `a short episode shorter than the threshold is complete almost immediately`() {
        // A 10s episode with the default 20s threshold - duration minus threshold is negative,
        // so any non-negative position satisfies "at or past" it.
        assertThat(CompletionRule.isComplete(positionMillis = 0, durationMillis = 10_000)).isTrue()
    }

    @Test
    fun `custom threshold is respected`() {
        assertThat(
            CompletionRule.isComplete(positionMillis = 90_000, durationMillis = 100_000, thresholdMillis = 5_000)
        ).isFalse()
        assertThat(
            CompletionRule.isComplete(positionMillis = 96_000, durationMillis = 100_000, thresholdMillis = 5_000)
        ).isTrue()
    }
}
