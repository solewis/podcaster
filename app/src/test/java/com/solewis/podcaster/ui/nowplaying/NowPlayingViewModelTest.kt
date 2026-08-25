package com.solewis.podcaster.ui.nowplaying

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.FakePlayback
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.awaitTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * A pure pass-through to [com.solewis.podcaster.player.Playback]. Worth pinning anyway because the
 * transport buttons are indistinguishable on screen if two of them are wired to the same call - a
 * mistake nothing else in the codebase would catch.
 */
class NowPlayingViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val playback = FakePlayback()
    private val viewModel = NowPlayingViewModel(playback)

    @Test
    fun playback_and_progress_are_surfaced_straight_from_the_player() = runTest(mainDispatcher.dispatcher) {
        playback.emitPlaying("ep-1")
        playback.emitProgress(positionMillis = 42_000, durationMillis = 300_000)

        assertThat(viewModel.playbackState.value.episodeId).isEqualTo("ep-1")
        assertThat(viewModel.playbackState.value.isPlaying).isTrue()
        assertThat(viewModel.progress.value.positionMillis).isEqualTo(42_000)
        assertThat(viewModel.progress.value.durationMillis).isEqualTo(300_000)
    }

    @Test
    fun each_transport_control_drives_its_own_command() = runTest(mainDispatcher.dispatcher) {
        viewModel.togglePlayPause()
        viewModel.skipForward()
        viewModel.skipBack()
        viewModel.seekTo(90_000)
        viewModel.setSpeed(1.5f)

        awaitTrue("every command reached the player") {
            playback.togglePlayPauseCount == 1 &&
                playback.skipForwardCount == 1 &&
                playback.skipBackCount == 1 &&
                playback.seekedTo == listOf(90_000L) &&
                playback.speedsSet == listOf(1.5f)
        }
    }
}
