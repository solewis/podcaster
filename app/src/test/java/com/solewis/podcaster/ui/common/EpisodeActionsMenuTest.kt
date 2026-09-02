package com.solewis.podcaster.ui.common

import androidx.compose.ui.test.junit4.v2.createComposeRule
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
 * The row menu that replaced three trailing icon buttons. Its download item changes label *and*
 * which callback it fires depending on state, which is where the damage would be: offering "Delete
 * download" to someone who meant to retry, or "Download" for something already on the device.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeActionsMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private var enqueued = 0
    private var downloaded = 0
    private var removed = 0
    private var toggledPlayed = 0

    private fun render(isPlayed: Boolean = false, download: EpisodeDownload? = null) {
        compose.setContent {
            PodcasterTheme {
                EpisodeActionsMenu(
                    episodeTitle = "Patient Zero",
                    isPlayed = isPlayed,
                    download = download,
                    onEnqueue = { enqueued++ },
                    onDownload = { downloaded++ },
                    onRemoveDownload = { removed++ },
                    onTogglePlayed = { toggledPlayed++ }
                )
            }
        }
    }

    private fun openMenu() {
        compose.onNodeWithTag(TestTags.episodeMenu("Patient Zero")).performClick()
        compose.waitForIdle()
    }

    private fun state(status: DownloadStatus, percent: Float = 0f) =
        EpisodeDownload("ep-1", status, percent)

    @Test
    fun the_actions_are_behind_the_menu_rather_than_on_the_row() {
        render()

        // Nothing is offered until the menu is opened - that is the whole point of the change, since
        // the row had run out of room for trailing controls.
        compose.onNodeWithText("Add to queue").assertDoesNotExist()

        openMenu()

        compose.onNodeWithText("Add to queue").assertExists()
    }

    @Test
    fun queueing_from_the_menu_reaches_the_callback() {
        render()
        openMenu()

        compose.onNodeWithTag(TestTags.MENU_ENQUEUE).performClick()

        assertThat(enqueued).isEqualTo(1)
    }

    @Test
    fun an_unplayed_episode_offers_to_mark_it_played() {
        render(isPlayed = false)
        openMenu()

        compose.onNodeWithText("Mark as finished").assertExists()
        compose.onNodeWithTag(TestTags.MENU_TOGGLE_PLAYED).performClick()

        assertThat(toggledPlayed).isEqualTo(1)
    }

    @Test
    fun a_played_episode_offers_the_opposite() {
        render(isPlayed = true)
        openMenu()

        compose.onNodeWithText("Mark as unfinished").assertExists()
    }

    @Test
    fun an_episode_with_no_download_offers_to_download_it() {
        render()
        openMenu()

        compose.onNodeWithTag(TestTags.MENU_DOWNLOAD).performClick()

        assertThat(downloaded).isEqualTo(1)
        assertThat(removed).isEqualTo(0)
    }

    @Test
    fun a_download_in_progress_offers_to_cancel_it() {
        render(download = state(DownloadStatus.DOWNLOADING, percent = 40f))
        openMenu()

        compose.onNodeWithText("Cancel download").assertExists()
        compose.onNodeWithTag(TestTags.MENU_DOWNLOAD).performClick()

        assertThat(removed).isEqualTo(1)
        assertThat(downloaded).isEqualTo(0)
    }

    @Test
    fun a_finished_download_names_the_action_and_confirms_it() {
        render(download = state(DownloadStatus.DOWNLOADED))
        openMenu()

        // Labelled, rather than the bare check mark this replaced - which gave no indication that
        // tapping it was the delete control at all.
        compose.onNodeWithText("Delete download").assertExists()
        compose.onNodeWithTag(TestTags.MENU_DOWNLOAD).performClick()
        compose.waitForIdle()

        assertThat(removed).isEqualTo(0)
        compose.onNodeWithText("Delete download?").assertExists()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertThat(removed).isEqualTo(1)
    }

    @Test
    fun a_failed_download_offers_a_retry_and_not_a_delete() {
        render(download = state(DownloadStatus.FAILED))
        openMenu()

        compose.onNodeWithText("Retry download").assertExists()
        compose.onNodeWithTag(TestTags.MENU_DOWNLOAD).performClick()

        // Deleting here would throw away the partial download a retry could resume from.
        assertThat(downloaded).isEqualTo(1)
        assertThat(removed).isEqualTo(0)
    }

    @Test
    fun a_download_being_deleted_offers_neither_action() {
        render(download = state(DownloadStatus.REMOVING))
        openMenu()

        // Either one would race the deletion already under way.
        compose.onNodeWithTag(TestTags.MENU_DOWNLOAD).assertDoesNotExist()
        compose.onNodeWithText("Add to queue").assertExists()
    }

    @Test
    fun the_row_label_says_what_a_download_is_doing() {
        // The state the menu cannot show, because you would have to open it to look.
        assertThat(downloadStatusLabel(null)).isNull()
        assertThat(downloadStatusLabel(state(DownloadStatus.DOWNLOADED))).isEqualTo("Downloaded")
        assertThat(downloadStatusLabel(state(DownloadStatus.DOWNLOADING, percent = 42.7f)))
            .isEqualTo("Downloading 42%")
        assertThat(downloadStatusLabel(state(DownloadStatus.FAILED))).isEqualTo("Download failed")
    }
}
