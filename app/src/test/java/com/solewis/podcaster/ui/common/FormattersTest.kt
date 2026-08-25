package com.solewis.podcaster.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.BeforeClass
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class FormattersTest {

    companion object {
        /**
         * `Formatters` builds its `DateTimeFormatter` at class-load time from the default locale,
         * so the locale has to be pinned before the first call rather than in a `@Before`. Month
         * names really are localized in the app - that is intended - so this pins the test, not
         * the behavior.
         */
        @BeforeClass
        @JvmStatic
        fun pinLocale() {
            Locale.setDefault(Locale.US)
        }
    }

    /** Built from a local date so the assertion holds in whatever zone the test machine is in. */
    private fun localMidnight(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun `episode date reads as a short human date`() {
        assertThat(formatEpisodeDate(localMidnight(2026, 3, 5))).isEqualTo("Mar 5, 2026")
    }

    @Test
    fun `episode date is absent rather than blank when a feed omits it`() {
        assertThat(formatEpisodeDate(null)).isNull()
    }

    @Test
    fun `duration under an hour omits the hour component`() {
        assertThat(formatDuration(25 * 60_000L)).isEqualTo("25m")
    }

    @Test
    fun `duration over an hour reads as hours and minutes`() {
        assertThat(formatDuration(77 * 60_000L)).isEqualTo("1h 17m")
    }

    @Test
    fun `duration drops the seconds rather than rounding up`() {
        // 25m59s still reads "25m" - a list is for scanning, not for precision.
        assertThat(formatDuration(25 * 60_000L + 59_000)).isEqualTo("25m")
    }

    @Test
    fun `an exact hour still shows zero minutes so the shape stays consistent`() {
        assertThat(formatDuration(60 * 60_000L)).isEqualTo("1h 0m")
    }

    @Test
    fun `unknown and nonsense durations are absent`() {
        assertThat(formatDuration(null)).isNull()
        assertThat(formatDuration(0)).isNull()
        assertThat(formatDuration(-5)).isNull()
    }

    @Test
    fun `remaining time counts down from the duration`() {
        assertThat(formatRemaining(positionMillis = 10 * 60_000L, durationMillis = 51 * 60_000L))
            .isEqualTo("41m left")
    }

    @Test
    fun `remaining time never goes negative when a stale position overshoots the duration`() {
        // Real cause: a feed-declared duration shorter than the actual audio. Without the clamp
        // this reads as a negative time remaining.
        assertThat(formatRemaining(positionMillis = 60 * 60_000L, durationMillis = 50 * 60_000L)).isNull()
    }

    @Test
    fun `remaining time is absent when the duration is unknown`() {
        assertThat(formatRemaining(positionMillis = 1_000, durationMillis = null)).isNull()
    }

    @Test
    fun `the scrubber clock pads minutes and seconds`() {
        assertThat(formatTimer(78_000)).isEqualTo("1:18")
        assertThat(formatTimer(9_000)).isEqualTo("0:09")
        assertThat(formatTimer(0)).isEqualTo("0:00")
    }

    @Test
    fun `the scrubber clock adds an hours field only once needed`() {
        assertThat(formatTimer(8_300_000)).isEqualTo("2:18:20")
        assertThat(formatTimer(59 * 60_000L + 59_000)).isEqualTo("59:59")
    }

    @Test
    fun `the scrubber clock is absent for unknown or negative values`() {
        assertThat(formatTimer(null)).isNull()
        assertThat(formatTimer(-1)).isNull()
    }
}
