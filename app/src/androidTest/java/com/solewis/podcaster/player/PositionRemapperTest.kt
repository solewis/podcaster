package com.solewis.podcaster.player

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PositionRemapper] against a real player, where the ordering of its callbacks - which differs from
 * what the code reads like - is the thing most likely to go wrong. See the JVM test for the
 * arithmetic itself.
 */
@RunWith(AndroidJUnit4::class)
class PositionRemapperTest {

    private lateinit var player: ExoPlayer
    private val log = PlaybackLog(java.io.File.createTempFile("remap-log", ".txt"))

    @Before
    fun setUp() = onMain {
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setLooper(Looper.getMainLooper())
            .build()
        player.addListener(PositionRemapper(player, log))
    }

    @After
    fun tearDown() = onMain { player.release() }

    /** Ten minutes of silence, presenting itself as an episode measured at [recordedMs] last time. */
    private fun load(startMs: Long, recordedMs: Long?) = onMain {
        val metadata = MediaMetadata.Builder().apply {
            recordedMs?.let { setExtras(Bundle().apply { putLong(PositionRemapper.EXTRA_RECORDED_DURATION_MS, it) }) }
        }.build()
        val source = SilenceMediaSource(DURATION_MS * 1_000).apply {
            updateMediaItem(MediaItem.Builder().setMediaId("ep").setMediaMetadata(metadata).build())
        }
        player.setMediaSource(source, startMs)
        player.prepare()
    }

    @Test
    fun a_position_saved_against_a_longer_copy_is_moved_into_this_one() {
        // Saved at 8:00 of a copy that ran 11:00; this one runs 10:00.
        load(startMs = 480_000, recordedMs = 660_000)

        // 480,000 x 600/660 = 436,364, less the 60,000 the copy shrank by.
        awaitPlayer("remapped") { onMain { player.currentPosition } in 375_000L..378_000L }
        assertThat(log.snapshot()).contains("REMAP")
    }

    @Test
    fun the_same_length_leaves_the_position_alone() {
        load(startMs = 480_000, recordedMs = DURATION_MS)

        awaitPlayer("loaded") { onMain { player.duration } == DURATION_MS }
        assertThat(onMain { player.currentPosition }).isWithin(500).of(480_000)
        assertThat(log.snapshot()).doesNotContain("REMAP")
    }

    @Test
    fun nothing_recorded_means_nothing_to_compare_against() {
        load(startMs = 480_000, recordedMs = null)

        awaitPlayer("loaded") { onMain { player.duration } == DURATION_MS }
        assertThat(onMain { player.currentPosition }).isWithin(500).of(480_000)
    }

    private companion object {
        const val DURATION_MS = 600_000L
    }
}
