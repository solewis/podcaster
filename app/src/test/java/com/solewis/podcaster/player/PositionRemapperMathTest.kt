package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.PlayableEpisode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where a saved position lands in a copy of a different length. The numbers in the first test are
 * the real ones from the phone log behind this.
 */
@RunWith(AndroidJUnit4::class)
class PositionRemapperMathTest {

    @Test
    fun the_reported_episode_resumes_before_its_end_instead_of_past_it() {
        // Stealing Bananas: measured at 138:47, saved at 134:05, came back at 133:33.
        val target = remappedStartPosition(
            savedMs = 8_045_181, recordedDurationMs = 8_327_078, newDurationMs = 8_013_426
        )!!

        assertThat(target).isLessThan(8_013_426)
        // Scaled (to ~129:02), then backed up by the whole 5:14 change - so nothing can have been
        // skipped, at the cost of possibly hearing that much again.
        assertThat(target).isWithin(1_000).of(7_428_500)
    }

    @Test
    fun a_longer_copy_moves_the_position_on_and_still_backs_up_by_the_change() {
        val target = remappedStartPosition(
            savedMs = 3_000_000, recordedDurationMs = 6_000_000, newDurationMs = 6_600_000
        )!!

        // 3,300,000 scaled, less the 600,000 the copy grew by.
        assertThat(target).isEqualTo(2_700_000)
    }

    @Test
    fun the_same_length_means_the_same_copy_and_nothing_moves() {
        assertThat(remappedStartPosition(3_000_000, 6_000_000, 6_000_000)).isNull()
        // Rounding, not a different copy.
        assertThat(remappedStartPosition(3_000_000, 6_000_000, 6_000_000 + LENGTH_TOLERANCE_MS)).isNull()
    }

    @Test
    fun a_remapped_position_never_lands_on_the_end_itself() {
        // Saved at the very end of a much longer copy: scaling and backing up still leaves it
        // inside, rather than handing the player a position that ends the episode at once.
        val target = remappedStartPosition(9_990_000, 10_000_000, 9_000_000)!!

        assertThat(target).isAtMost(9_000_000 - 10_000)
    }

    @Test
    fun a_position_near_the_start_cannot_go_negative() {
        assertThat(remappedStartPosition(60_000, 6_000_000, 5_000_000)).isEqualTo(0)
    }

    @Test
    fun only_a_duration_the_player_measured_is_carried_on_the_item() {
        val measured = PlayableEpisode(
            episodeId = "e", title = "t", podcastTitle = "p", artworkUrl = null,
            mediaUrl = "https://example.com/e.mp3", startPositionMillis = 10_000,
            durationMillis = 600_000, recordedDurationMillis = 600_000
        )
        val estimated = measured.copy(recordedDurationMillis = null)

        assertThat(MediaItemMapper.toMediaItem(measured).mediaMetadata.extras
            ?.getLong(PositionRemapper.EXTRA_RECORDED_DURATION_MS)).isEqualTo(600_000)
        // A feed's estimate is not what the position was saved against, so it must not drive a move.
        assertThat(MediaItemMapper.toMediaItem(estimated).mediaMetadata.extras).isNull()
    }
}
