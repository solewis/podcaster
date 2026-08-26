package com.solewis.podcaster.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the car steering-wheel `<<` / `>>` buttons. Those reach the app as next/previous -
 * Android Auto has no concept of "skip 15 seconds" to send - and this is the layer that turns them
 * into seeks. The two were broken differently, so each is pinned separately below; see
 * [TimedSkipPlayer] for the mechanism.
 *
 * Runs against a real [ExoPlayer] with a real prepared source rather than a fake, because the whole
 * bug lived in ExoPlayer's own command-availability calculation: a fake would have to hardcode the
 * very answer under test.
 */
@RunWith(AndroidJUnit4::class)
class TimedSkipPlayerTest {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var sessionPlayer: TimedSkipPlayer

    @Before
    fun setUp() {
        onMain {
            exoPlayer = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                .setSeekBackIncrementMs(SKIP_MILLIS)
                .setSeekForwardIncrementMs(SKIP_MILLIS)
                .setLooper(Looper.getMainLooper())
                .build()
            sessionPlayer = TimedSkipPlayer(exoPlayer)
            // Silence rather than a real episode: it prepares instantly, needs no network, and is
            // seekable with a known duration, which is all the seek assertions below depend on.
            exoPlayer.setMediaSource(
                SilenceMediaSource.Factory().setDurationUs(EPISODE_MILLIS * 1_000).createMediaSource()
            )
            exoPlayer.prepare()
        }
        awaitReady()
    }

    @After
    fun tearDown() {
        onMain { sessionPlayer.release() }
    }

    @Test
    fun offers_next_even_though_an_episode_plays_as_a_single_item_playlist() {
        onMain {
            // Half the premise of the bug: with no adjacent item ExoPlayer withholds this
            // command, so the session dropped every `>>` press on its availability check.
            assertThat(exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isFalse()

            assertThat(sessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)).isTrue()
            assertThat(sessionPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue()
        }
    }

    @Test
    fun previous_no_longer_restarts_the_episode_the_way_the_wrapped_player_would() {
        // The other half of the premise, and the more annoying one: previous *is* offered on a
        // single-item playlist, so `<<` was live - it just jumped to zero instead of backing up.
        onMain { assertThat(exoPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)).isTrue() }

        seekTo(START_MILLIS)
        onMain { exoPlayer.seekToPrevious() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(0)

        seekTo(START_MILLIS)
        onMain { sessionPlayer.seekToPrevious() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS - SKIP_MILLIS)
    }

    @Test
    fun next_seeks_forward_by_the_skip_increment_instead_of_leaving_the_episode() {
        seekTo(START_MILLIS)

        onMain { sessionPlayer.seekToNext() }

        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS + SKIP_MILLIS)
        assertThat(onMain { exoPlayer.currentMediaItemIndex }).isEqualTo(0)
    }


    @Test
    fun a_configured_amount_is_what_actually_gets_seeked_not_just_what_is_reported() {
        // The trap this guards: ForwardingSimpleBasePlayer implements COMMAND_SEEK_BACK as
        // seekBack() on the *wrapped* player, whose increment is fixed when it is built. Report a
        // different number from getState() and you get a button that says 30 and moves 15 - which
        // is invisible until someone counts. Note the wrapped player below is still built at 15s.
        val configured = onMain {
            TimedSkipPlayer(exoPlayer, skipBackMillis = { 30_000L }, skipForwardMillis = { 5_000L })
        }

        onMain {
            assertThat(configured.seekBackIncrement).isEqualTo(30_000L)
            assertThat(configured.seekForwardIncrement).isEqualTo(5_000L)
        }

        seekTo(START_MILLIS)
        onMain { configured.seekBack() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS - 30_000L)

        seekTo(START_MILLIS)
        onMain { configured.seekForward() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS + 5_000L)
    }

    @Test
    fun the_steering_wheel_buttons_use_the_configured_amounts_too() {
        // Same values have to reach next/previous, since that is the only route a head unit has.
        val configured = onMain {
            TimedSkipPlayer(exoPlayer, skipBackMillis = { 30_000L }, skipForwardMillis = { 5_000L })
        }

        seekTo(START_MILLIS)
        onMain { configured.seekToPrevious() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS - 30_000L)

        seekTo(START_MILLIS)
        onMain { configured.seekToNext() }
        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(START_MILLIS + 5_000L)
    }

    @Test
    fun skipping_back_at_the_start_stops_at_zero_rather_than_going_negative() {
        seekTo(2_000)

        onMain { sessionPlayer.seekBack() }

        assertThat(positionMillis()).isWithin(TOLERANCE_MILLIS).of(0)
    }

    private fun positionMillis(): Long = onMain { sessionPlayer.currentPosition }

    private fun seekTo(positionMillis: Long) {
        onMain { exoPlayer.seekTo(positionMillis) }
        awaitTrue { onMain { exoPlayer.currentPosition } >= positionMillis }
    }


    private fun awaitReady() = awaitTrue {
        onMain { exoPlayer.playbackState } == Player.STATE_READY
    }

    /** ExoPlayer's work is asynchronous even for silence, so every assertion has to wait it out. */
    private fun awaitTrue(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_MILLIS)
        }
        throw AssertionError("Condition still false after ${TIMEOUT_MILLIS}ms")
    }

    /**
     * Both players insist on their application thread, and the instrumentation thread this test
     * body runs on is not it.
     */
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private companion object {
        const val SKIP_MILLIS = 15_000L
        const val EPISODE_MILLIS = 600_000L
        const val START_MILLIS = 120_000L
        const val TOLERANCE_MILLIS = 1_000L
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_MILLIS = 50L
    }
}
