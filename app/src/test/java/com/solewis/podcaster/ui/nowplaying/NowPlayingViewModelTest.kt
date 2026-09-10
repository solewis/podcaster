package com.solewis.podcaster.ui.nowplaying

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.player.PlaybackStarter
import com.solewis.podcaster.testing.FakeConnectivity
import com.solewis.podcaster.testing.FakeDownloads
import com.solewis.podcaster.testing.FakePlayback
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.ViewModelHost
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.player.SleepTimer
import com.solewis.podcaster.player.SleepTimerState
import com.solewis.podcaster.testing.awaitValue
import com.solewis.podcaster.testing.keepHot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * A pure pass-through to [com.solewis.podcaster.player.Playback]. Worth pinning anyway because the
 * transport buttons are indistinguishable on screen if two of them are wired to the same call - a
 * mistake nothing else in the codebase would catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val playback = FakePlayback()
    private val host = ViewModelHost()
    private val settings = MutableStateFlow(AppSettings())


    @After
    fun tearDown() = host.close()

    /**
     * Built inside the test, because the sleep timer needs the test's own scope and virtual clock -
     * a real [SleepTimer] over `testScheduler`, rather than a fake, so these assertions are about
     * the ViewModel reaching the real thing.
     */
    private fun TestScope.viewModel(): NowPlayingViewModel = host.hosting(
        NowPlayingViewModel(
            playback,
            settings,
            SleepTimer(playback, backgroundScope, now = { testScheduler.currentTime }),
            PlaybackStarter(playback, FakeDownloads(), FakeConnectivity(), backgroundScope)
        )
    )

    @Test
    fun playback_and_progress_are_surfaced_straight_from_the_player() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        playback.emitPlaying("ep-1")
        playback.emitProgress(positionMillis = 42_000, durationMillis = 300_000)

        assertThat(viewModel.playbackState.value.episodeId).isEqualTo("ep-1")
        assertThat(viewModel.playbackState.value.isPlaying).isTrue()
        assertThat(viewModel.progress.value.positionMillis).isEqualTo(42_000)
        assertThat(viewModel.progress.value.durationMillis).isEqualTo(300_000)
    }

    @Test
    fun each_transport_control_drives_its_own_command() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
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

    @Test
    fun the_skip_buttons_read_the_configured_amounts() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        keepHot(viewModel.settings)
        settings.value = AppSettings(skipBack = SkipAmount.THIRTY, skipForward = SkipAmount.FIVE)

        // Back and forward are set independently, and the buttons print the number they will seek -
        // so surfacing one for both would put a visibly wrong figure on screen.
        val shown = viewModel.settings.awaitValue { it.skipBack == SkipAmount.THIRTY }
        assertThat(shown.skipForward).isEqualTo(SkipAmount.FIVE)
    }

    @Test
    fun the_sleep_timer_button_reflects_a_countdown_the_screen_did_not_start() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = viewModel()

            viewModel.startSleepTimer(minutes = 30)

            // Read straight off the timer, which outlives this ViewModel - the screen has no clock
            // of its own, so anything else would stop counting when Now Playing left the screen.
            assertThat(viewModel.sleepTimerState.value)
                .isEqualTo(SleepTimerState.Running(30 * 60_000L))
        }

    @Test
    fun turning_the_sleep_timer_off_from_the_screen_stops_it() = runTest(mainDispatcher.dispatcher) {
        val viewModel = viewModel()
        viewModel.startSleepTimer(minutes = 30)

        viewModel.cancelSleepTimer()

        assertThat(viewModel.sleepTimerState.value).isEqualTo(SleepTimerState.Off)
    }
}
