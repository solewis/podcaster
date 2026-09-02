package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.EpisodeDownload

/**
 * Every per-episode action except playing, behind one `⋮` on a list row.
 *
 * This exists because trailing icon buttons ran out of room. A row carried play, add-to-queue and
 * download - three 48dp targets - which on a 320dp-wide screen leaves roughly 80px for the episode
 * title, and there was no fourth slot for marking played. Collapsing everything but play into a
 * menu gives the title back most of that width and makes the set of actions extensible, which
 * matters because the Home feed and a show's episode list should offer the same ones.
 *
 * Play deliberately stays a direct button: it is the reason the row exists, and burying the primary
 * action of a media app behind a menu would be perverse.
 *
 * Download *progress* is not shown here - a menu you have to open is no place for a progress
 * indicator. The row's own metadata line carries it instead; see [downloadStatusLabel].
 */
@Composable
fun EpisodeActionsMenu(
    episodeTitle: String,
    isPlayed: Boolean,
    download: EpisodeDownload?,
    onEnqueue: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onTogglePlayed: () -> Unit,
    modifier: Modifier = Modifier
) {
    var open by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        DeleteDownloadDialog(
            episodeTitle = episodeTitle,
            onConfirm = { confirmingDelete = false; onRemoveDownload() },
            onDismiss = { confirmingDelete = false }
        )
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.testTag(TestTags.episodeMenu(episodeTitle))
        ) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions for $episodeTitle")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(
                text = "Add to queue",
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                tag = TestTags.MENU_ENQUEUE
            ) {
                open = false
                onEnqueue()
            }
            MenuItem(
                // "finished", to match the word the row's own metadata line uses. A menu that
                // set an episode to "played" while the line then read "Finished" left it unclear
                // whether they were the same state.
                text = if (isPlayed) "Mark as unfinished" else "Mark as finished",
                icon = if (isPlayed) Icons.Default.RemoveDone else Icons.Default.DoneAll,
                tag = TestTags.MENU_TOGGLE_PLAYED
            ) {
                open = false
                onTogglePlayed()
            }
            DownloadMenuItem(
                download = download,
                onDownload = {
                    open = false
                    onDownload()
                },
                // Only a finished download is worth confirming; cancelling one in flight has
                // nothing to lose but the time already spent.
                onRemove = {
                    open = false
                    if (download?.status == DownloadStatus.DOWNLOADED) {
                        confirmingDelete = true
                    } else {
                        onRemoveDownload()
                    }
                }
            )
        }
    }
}

/**
 * One item covering every download state, so the menu never offers "Download" for something already
 * on the device - or, worse, silently deletes when the user meant to retry.
 */
@Composable
private fun DownloadMenuItem(
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemove: () -> Unit
) {
    when (download?.status) {
        null -> MenuItem("Download", Icons.Default.Download, TestTags.MENU_DOWNLOAD, onDownload)
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING ->
            MenuItem("Cancel download", Icons.Default.Close, TestTags.MENU_DOWNLOAD, onRemove)
        DownloadStatus.DOWNLOADED ->
            MenuItem("Delete download", Icons.Default.Delete, TestTags.MENU_DOWNLOAD, onRemove)
        DownloadStatus.FAILED ->
            MenuItem("Retry download", Icons.Default.Refresh, TestTags.MENU_DOWNLOAD, onDownload)
        // Already on its way out; offering either action would race the deletion.
        DownloadStatus.REMOVING -> Unit
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier.testTag(tag)
    )
}

/**
 * The download half of a row's metadata line, e.g. "Downloaded" or "Downloading 42%". Null when
 * there is nothing to say, so the line reads exactly as it did before downloads existed.
 *
 * Text rather than an icon in the trailing controls: this is the one piece of download state that
 * has to be visible without opening anything, and the metadata line has room where the controls
 * do not.
 */
fun downloadStatusLabel(download: EpisodeDownload?): String? = when (download?.status) {
    null -> null
    DownloadStatus.DOWNLOADED -> "Downloaded"
    DownloadStatus.DOWNLOADING -> "Downloading ${download.percent.toInt()}%"
    DownloadStatus.QUEUED -> "Queued"
    DownloadStatus.FAILED -> "Download failed"
    DownloadStatus.REMOVING -> "Deleting"
}
