package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.data.repo.EpisodeDownload

/**
 * One control covering every download state, because they are all the same affordance from the
 * user's side: this episode is or is not on the device, and tapping changes that.
 *
 * A determinate ring while downloading rather than a spinner - a podcast episode is tens of
 * megabytes over a phone connection, long enough that "is this actually progressing" is a real
 * question. Failed shows an error the tap retries, rather than silently reverting to the download
 * icon, which would look like the tap never registered.
 */
@Composable
fun DownloadButton(
    episodeTitle: String,
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = download?.status
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        DeleteDownloadDialog(
            episodeTitle = episodeTitle,
            onConfirm = { confirmingDelete = false; onRemove() },
            onDismiss = { confirmingDelete = false }
        )
    }

    IconButton(
        onClick = {
            when (status) {
                null, DownloadStatus.FAILED -> onDownload()
                // An unlabelled icon that silently discards tens of megabytes is not an obvious
                // enough affordance for what it does - so a finished download is confirmed first.
                DownloadStatus.DOWNLOADED -> confirmingDelete = true
                // Cancelling an in-flight download needs no ceremony; Media3 drops the partial
                // data either way.
                else -> onRemove()
            }
        },
        enabled = status != DownloadStatus.REMOVING,
        modifier = modifier.testTag(TestTags.downloadButton(status))
    ) {
        when (status) {
            null -> Icon(
                Icons.Default.Download,
                contentDescription = "Download this episode"
            )

            DownloadStatus.QUEUED -> Icon(
                Icons.Default.Download,
                contentDescription = "Queued for download - tap to cancel",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DownloadStatus.DOWNLOADING -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { download.percent / 100f },
                    modifier = Modifier.size(20.dp)
                )
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Downloading - tap to cancel",
                    modifier = Modifier.size(12.dp)
                )
            }

            DownloadStatus.DOWNLOADED -> Icon(
                Icons.Default.DownloadDone,
                contentDescription = "Downloaded - tap to delete",
                tint = MaterialTheme.colorScheme.primary
            )

            DownloadStatus.FAILED -> Icon(
                Icons.Default.ErrorOutline,
                contentDescription = "Download failed - tap to retry",
                tint = MaterialTheme.colorScheme.error
            )

            DownloadStatus.REMOVING -> Icon(
                Icons.Default.Download,
                contentDescription = "Deleting",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
