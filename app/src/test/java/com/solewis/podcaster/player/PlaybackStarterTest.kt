package com.solewis.podcaster.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.testing.FakeConnectivity
import com.solewis.podcaster.testing.FakeDownloads
import com.solewis.podcaster.testing.FakePlayback
import com.solewis.podcaster.testing.MainDispatcherRule
import com.solewis.podcaster.testing.awaitTrue
import com.solewis.podcaster.testing.settle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Starting an episode, and the two things that used to be missing when you did.
 *
 * With no connection, playback would sit inside ExoPlayer's retries with nothing on screen, which
 * reads as the app having frozen rather than as a failure. And the wait between tapping play and
 * hearing anything was shown on the Home feed and nowhere else.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackStarterTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val playback = FakePlayback()
    private val downloads = FakeDownloads()
    private val connectivity = FakeConnectivity()

    private fun TestScope.starter() =
        PlaybackStarter(playback, downloads, connectivity, backgroundScope)

    private fun episode(id: String = "ep-1") = PlayableEpisode(
        episodeId = id,
        title = "An Episode",
        podcastTitle = "A Show",
        artworkUrl = null,
        mediaUrl = "https://example.com/$id.mp3",
        startPositionMillis = 0
    )

    @Test
    fun an_episode_plays_normally_when_there_is_a_connection() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()

        starter.start(episode())

        assertThat(playback.played.single().episodeId).isEqualTo("ep-1")
    }

    @Test
    fun with_no_connection_it_refuses_rather_than_hanging() = runTest(mainDispatcher.dispatcher) {
        connectivity.online = false
        val starter = starter()

        starter.start(episode())

        // Never handed to the player: letting it through would spend ExoPlayer's retry budget on a
        // connection that is not there, which from the outside is indistinguishable from a freeze.
        assertThat(playback.played).isEmpty()
    }

    @Test
    fun refusing_says_why() = runTest(mainDispatcher.dispatcher) {
        connectivity.online = false
        val starter = starter()
        val seen = mutableListOf<String>()
        backgroundScope.launch { starter.messages.collect { seen += it } }
        settle()

        starter.start(episode())

        awaitTrue("the refusal was reported") { seen == listOf(PlaybackStarter.NO_CONNECTION_MESSAGE) }
    }

    @Test
    fun a_downloaded_episode_plays_with_no_connection_at_all() = runTest(mainDispatcher.dispatcher) {
        connectivity.online = false
        downloads.emit("ep-1", DownloadStatus.DOWNLOADED)
        val starter = starter()

        starter.start(episode())

        // The entire point of downloading. Refusing here would make the feature pointless in the
        // one situation it exists for.
        assertThat(playback.played.single().episodeId).isEqualTo("ep-1")
    }

    @Test
    fun a_partial_download_is_not_good_enough_to_play_offline() = runTest(mainDispatcher.dispatcher) {
        connectivity.online = false
        downloads.emit("ep-1", DownloadStatus.DOWNLOADING, percent = 80f)
        val starter = starter()

        starter.start(episode())

        // Eighty percent of an episode is still an episode that stops mid-sentence with no way to
        // fetch the rest.
        assertThat(playback.played).isEmpty()
    }

    @Test
    fun the_tapped_episode_is_pending_until_it_is_audible() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()

        starter.start(episode())

        // FakePlayback deliberately does not become audible on its own, which is the real gap being
        // represented: controller connection, then buffering.
        assertThat(starter.pendingEpisodeId.value).isEqualTo("ep-1")
    }

    @Test
    fun the_pending_episode_clears_once_it_starts_making_sound() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()
        starter.start(episode())

        playback.emitPlaying("ep-1")

        awaitTrue("the spinner came down") { starter.pendingEpisodeId.value == null }
    }

    @Test
    fun a_pause_does_not_put_the_spinner_back() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()
        starter.start(episode())
        playback.emitPlaying("ep-1")
        awaitTrue("started") { starter.pendingEpisodeId.value == null }

        playback.emitPaused("ep-1")

        // Merely masking the pending id while isPlaying holds looks identical until you pause,
        // which un-masks it and puts a spinner back on something that started long ago.
        settle()
        assertThat(starter.pendingEpisodeId.value).isNull()
    }

    @Test
    fun a_tap_that_never_produces_sound_is_released_by_the_next_episode() =
        runTest(mainDispatcher.dispatcher) {
            val starter = starter()
            starter.start(episode("ep-1"))

            playback.emitPlaying("ep-2")

            // Otherwise a failed start leaves a spinner on that row for the rest of the session.
            awaitTrue("the stale spinner cleared") { starter.pendingEpisodeId.value == null }
        }

    @Test
    fun a_refused_start_leaves_no_spinner_behind() = runTest(mainDispatcher.dispatcher) {
        connectivity.online = false
        val starter = starter()

        starter.start(episode())

        // Nothing is coming, so a spinner would sit there indefinitely - the exact hang this is
        // meant to replace.
        assertThat(starter.pendingEpisodeId.value).isNull()
    }

    @Test
    fun resuming_a_loaded_episode_offline_refuses_rather_than_doing_nothing() =
        runTest(mainDispatcher.dispatcher) {
            val starter = starter()
            playback.emitPaused("ep-1")
            connectivity.online = false

            starter.togglePlayPause()

            // The original hang, reachable from the one control most likely to be pressed after
            // playback stops: the guard used to cover only *new* episodes, so resuming a loaded
            // one with no network silently did nothing at all.
            assertThat(playback.togglePlayPauseCount).isEqualTo(0)
        }

    @Test
    fun resuming_a_downloaded_episode_offline_still_works() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()
        playback.emitPaused("ep-1")
        downloads.emit("ep-1", DownloadStatus.DOWNLOADED)
        connectivity.online = false

        starter.togglePlayPause()

        awaitTrue("resumed") { playback.togglePlayPauseCount == 1 }
    }

    @Test
    fun pausing_is_never_blocked_by_the_network() = runTest(mainDispatcher.dispatcher) {
        val starter = starter()
        playback.emitPlaying("ep-1")
        connectivity.online = false

        starter.togglePlayPause()

        // Refusing to *stop* audio because there is no connection would be absurd.
        awaitTrue("paused") { playback.togglePlayPauseCount == 1 }
    }
}
