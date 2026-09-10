package com.solewis.podcaster.player

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The log has to still be readable after a week of listening, and has to survive being written to
 * by a process that is killed at arbitrary points - so the interesting cases are the cap and the
 * trim, not the happy path.
 */
class PlaybackLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun log(maxBytes: Long = 1024) =
        PlaybackLog(folder.newFile("playback-log.txt"), maxBytes)

    @Test
    fun an_event_is_recorded_with_its_detail() {
        val log = log()

        log.record("CMD_SEEK", "to=90000")

        assertThat(log.snapshot()).contains("CMD_SEEK to=90000")
    }

    @Test
    fun every_line_is_timestamped_so_a_reported_time_can_be_found_in_it() {
        val log = log()

        log.record("STATE", "READY")

        // The whole point of the file is that someone says "it happened about 08:14" and the log
        // can be searched for it.
        assertThat(log.snapshot().trim()).matches("""\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d STATE READY""")
    }

    @Test
    fun events_are_kept_in_order() {
        val log = log()

        log.record("FIRST")
        log.record("SECOND")
        log.record("THIRD")

        val lines = log.snapshot().trim().lines()
        assertThat(lines.map { it.substringAfterLast(' ') }).containsExactly("FIRST", "SECOND", "THIRD").inOrder()
    }

    @Test
    fun the_log_stays_under_its_cap_however_long_it_runs() {
        val log = log(maxBytes = 2048)

        repeat(500) { log.record("DISCONTINUITY", "reason=SEEK from=$it to=${it + 1000}") }

        // Left uncapped this would be about 25kB. A diagnostic nobody has to remember to turn off
        // is only safe if it cannot grow without bound.
        assertThat(folder.root.listFiles()!!.single().length()).isAtMost(2048 + 512)
    }

    @Test
    fun trimming_keeps_the_most_recent_events_and_drops_the_oldest() {
        val log = log(maxBytes = 1024)

        repeat(200) { log.record("EVENT", "n=$it") }

        val snapshot = log.snapshot()
        // The recent past is the only part anyone reads, so that is the half that must survive.
        assertThat(snapshot).contains("n=199")
        assertThat(snapshot).doesNotContain("n=0 ")
    }

    @Test
    fun trimming_never_leaves_a_half_line_at_the_start() {
        val log = log(maxBytes = 512)

        repeat(200) { log.record("EVENT", "n=$it") }

        // Cutting at a byte offset lands mid-line; a log whose first line is "9 to=1009" reads as
        // corruption and invites the reader to distrust the rest of it.
        val first = log.snapshot().lines().first()
        assertThat(first).matches("""\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d EVENT n=\d+""")
    }

    @Test
    fun clearing_empties_it() {
        val log = log()
        log.record("EVENT")

        log.clear()

        assertThat(log.snapshot()).isEmpty()
    }

    @Test
    fun a_log_whose_file_cannot_be_written_does_not_bring_down_playback() {
        // The directory, not a file in it - every write will fail. A diagnostic that can crash the
        // thing it is diagnosing is worse than no diagnostic.
        val log = PlaybackLog(folder.newFolder("not-a-file"))

        log.record("EVENT", "detail")

        assertThat(log.snapshot()).isEmpty()
    }
}
