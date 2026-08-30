package com.solewis.podcaster.ui.common

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One control standing in for six states, which is the whole risk: the states are told apart only
 * by an icon and a tap behaviour, so rendering the wrong one - or worse, wiring a tap to the wrong
 * call - looks entirely plausible on screen. Deleting an episode you meant to download is the
 * failure that matters.
 */
@RunWith(AndroidJUnit4::class)
class DownloadButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private var downloadCalls = 0
    private var removeCalls = 0

    private fun render(download: EpisodeDownload?) {
        compose.setContent {
            PodcasterTheme {
                DownloadButton(
                    episodeTitle = "Patient Zero",
                    download = download,
                    onDownload = { downloadCalls++ },
                    onRemove = { removeCalls++ }
                )
            }
        }
    }

    private fun state(status: DownloadStatus, percent: Float = 0f) =
        EpisodeDownload("ep-1", status, percent)

    @Test
    fun an_episode_that_is_not_downloaded_offers_to_download_it() {
        render(null)

        compose.onNodeWithTag(TestTags.downloadButton(null)).performClick()

        assertThat(downloadCalls).isEqualTo(1)
        assertThat(removeCalls).isEqualTo(0)
    }

    @Test
    fun tapping_a_download_in_progress_cancels_rather_than_starting_a_second_one() {
        render(state(DownloadStatus.DOWNLOADING, percent = 42f))

        compose.onNodeWithContentDescription("Downloading - tap to cancel").performClick()

        assertThat(removeCalls).isEqualTo(1)
        assertThat(downloadCalls).isEqualTo(0)
    }

    @Test
    fun deleting_a_finished_download_asks_first() {
        render(state(DownloadStatus.DOWNLOADED))

        compose.onNodeWithContentDescription("Downloaded - tap to delete").performClick()
        compose.waitForIdle()

        // An unlabelled icon quietly discarding tens of megabytes is not an obvious enough
        // affordance for what it does.
        assertThat(removeCalls).isEqualTo(0)
        compose.onNodeWithText("Delete download?").assertExists()
    }

    @Test
    fun confirming_the_dialog_deletes_it() {
        render(state(DownloadStatus.DOWNLOADED))
        compose.onNodeWithContentDescription("Downloaded - tap to delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()

        assertThat(removeCalls).isEqualTo(1)
    }

    @Test
    fun keeping_it_changes_nothing() {
        render(state(DownloadStatus.DOWNLOADED))
        compose.onNodeWithContentDescription("Downloaded - tap to delete").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Keep").performClick()
        compose.waitForIdle()

        assertThat(removeCalls).isEqualTo(0)
    }

    @Test
    fun a_failed_download_retries_rather_than_deleting() {
        render(state(DownloadStatus.FAILED))

        // The one state where the tap re-downloads. Treating failure as "already have it" and
        // deleting would throw away the partial download the retry could have resumed from.
        compose.onNodeWithContentDescription("Download failed - tap to retry").performClick()

        assertThat(downloadCalls).isEqualTo(1)
        assertThat(removeCalls).isEqualTo(0)
    }

    @Test
    fun a_download_being_deleted_cannot_be_tapped_again() {
        render(state(DownloadStatus.REMOVING))

        compose.onNodeWithTag(TestTags.downloadButton(DownloadStatus.REMOVING)).assertIsNotEnabled()
    }

    @Test
    fun each_state_renders_as_itself() {
        // Guards the mapping as a whole: a state falling through to the wrong branch is invisible
        // on screen but changes what a tap does.
        render(state(DownloadStatus.QUEUED))

        compose.onNodeWithTag(TestTags.downloadButton(DownloadStatus.QUEUED)).assertExists()
        compose.onNodeWithContentDescription("Queued for download - tap to cancel").assertExists()
    }
}
