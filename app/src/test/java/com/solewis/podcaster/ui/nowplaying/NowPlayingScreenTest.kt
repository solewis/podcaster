package com.solewis.podcaster.ui.nowplaying

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.settings.AppSettings
import com.solewis.podcaster.player.PlaybackStarter
import com.solewis.podcaster.player.SleepTimer
import com.solewis.podcaster.testing.FakeConnectivity
import com.solewis.podcaster.testing.FakeDownloads
import com.solewis.podcaster.testing.FakePlayback
import com.solewis.podcaster.testing.ViewModelHost
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.theme.PodcasterTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one piece of navigation this screen offers off itself: a way to see what an episode is
 * actually about, which nothing on Now Playing states beyond a title truncated over the artwork.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val playback = FakePlayback()
    private val host = ViewModelHost()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() {
        host.close()
        scope.cancel()
    }

    private fun launch(): MutableList<String> {
        val opened = mutableListOf<String>()
        val viewModel = host.hosting(
            NowPlayingViewModel(
                playback,
                MutableStateFlow(AppSettings()),
                SleepTimer(playback, scope),
                PlaybackStarter(playback, FakeDownloads(), FakeConnectivity(), scope)
            )
        )
        compose.setContent {
            PodcasterTheme {
                NowPlayingScreen(viewModel = viewModel, onBack = {}, onOpenEpisode = { opened += it })
            }
        }
        return opened
    }

    @Test
    fun nothing_is_playing_shows_no_info_button() {
        launch()

        compose.onNodeWithTag(TestTags.NOW_PLAYING_INFO).assertDoesNotExist()
    }

    @Test
    fun tapping_info_opens_the_playing_episodes_details() {
        val opened = launch()
        playback.emitPlaying("ep-1")
        compose.waitForIdle()

        compose.onNodeWithTag(TestTags.NOW_PLAYING_INFO).performScrollTo().performClick()

        assertThat(opened).containsExactly("ep-1")
    }
}
