package com.solewis.podcaster.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * One rule shared by four screens - the Home feed, a show's episode list, the episode detail screen
 * and the mini player - so an episode has to read identically wherever you meet it. Tested here
 * rather than through any one of those screens because it is plain logic, and because a screen test
 * would only ever cover the screen it was written for.
 */
class EpisodeProgressUiTest {

    private val fiftyOneMinutes = 51 * 60_000L

    @Test
    fun an_untouched_episode_shows_its_full_length_and_no_bar() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = fiftyOneMinutes,
            positionMillis = 0,
            isPlayed = false
        )

        assertThat(ui.label).isEqualTo("51m")
        assertThat(ui.showBar).isFalse()
    }

    @Test
    fun a_part_listened_episode_shows_time_remaining_and_a_bar() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = fiftyOneMinutes,
            positionMillis = 10 * 60_000L,
            isPlayed = false
        )

        assertThat(ui.label).isEqualTo("41m left")
        assertThat(ui.showBar).isTrue()
        assertThat(ui.positionMillis).isEqualTo(10 * 60_000L)
        assertThat(ui.durationMillis).isEqualTo(fiftyOneMinutes)
    }

    @Test
    fun a_finished_episode_says_so_and_drops_the_bar() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = fiftyOneMinutes,
            positionMillis = fiftyOneMinutes,
            isPlayed = true
        )

        // "Played" wins over any leftover position - a full bar next to it would just be noise.
        assertThat(ui.label).isEqualTo("Played")
        assertThat(ui.showBar).isFalse()
    }

    @Test
    fun a_played_flag_beats_a_stored_position_even_partway_through() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = fiftyOneMinutes,
            positionMillis = 5 * 60_000L,
            isPlayed = true
        )

        assertThat(ui.label).isEqualTo("Played")
    }

    @Test
    fun a_missing_duration_leaves_no_bar_even_with_a_stored_position() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = null,
            positionMillis = 60_000,
            isPlayed = false
        )

        // Nothing to draw a bar against, and no honest "time left" to state.
        assertThat(ui.label).isEmpty()
        assertThat(ui.showBar).isFalse()
    }

    @Test
    fun the_live_position_overrides_the_stored_one_for_the_playing_episode() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = fiftyOneMinutes,
            positionMillis = 10 * 60_000L,
            isPlayed = false,
            livePositionMillis = 30 * 60_000L
        )

        // While an episode is playing its row has to track the player, not the last DB write -
        // progress is only flushed to Room every 5 seconds.
        assertThat(ui.label).isEqualTo("21m left")
        assertThat(ui.positionMillis).isEqualTo(30 * 60_000L)
    }

    @Test
    fun a_live_duration_fills_in_for_a_feed_that_declared_none() {
        val ui = episodeProgressUi(
            pubDateMillis = null,
            durationMillis = null,
            positionMillis = 0,
            isPlayed = false,
            livePositionMillis = 60_000,
            liveDurationMillis = 600_000
        )

        // The player knows the real length even when the feed didn't declare one.
        assertThat(ui.label).isEqualTo("9m left")
        assertThat(ui.showBar).isTrue()
    }

    @Test
    fun the_publication_date_leads_the_label_when_there_is_one() {
        val ui = episodeProgressUi(
            pubDateMillis = 1_700_000_000_000,
            durationMillis = fiftyOneMinutes,
            positionMillis = 0,
            isPlayed = false
        )

        assertThat(ui.label).contains(" · 51m")
        assertThat(ui.label.startsWith("\u00b7")).isFalse()
    }
}
