package com.solewis.podcaster.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.repo.DownloadStatus
import com.solewis.podcaster.ui.common.EmptyState
import com.solewis.podcaster.ui.common.EpisodeActionsMenu
import com.solewis.podcaster.ui.common.EpisodeArtworkSize
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.common.formatBytes

/**
 * The Downloads segment of the Activity tab. A segment rather than its own tab because it answers
 * the same question as Queue - what is lined up for you - and the tab strip is already three wide.
 */
@Composable
fun DownloadsList(
    viewModel: DownloadsViewModel,
    onOpenEpisode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rows by viewModel.rows.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()

    if (rows.isEmpty()) {
        // Names the menu, not an icon: downloading moved behind an episode row's overflow when the
        // trailing controls ran out of room, so "tap the download icon" had stopped being true.
        EmptyState("Nothing downloaded yet - use an episode's menu to keep it offline.", modifier)
        return
    }

    Column(modifier = modifier.testTag(TestTags.DOWNLOADS_LIST)) {
        Text(
            // The running total is the whole reason to have this screen rather than just a filter
            // on the episode list, so it goes at the top rather than buried under the rows.
            "${rows.size} ${if (rows.size == 1) "episode" else "episodes"} · ${formatBytes(totalBytes)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        HorizontalDivider()
        LazyColumn {
            items(rows, key = { it.episode.id }) { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenEpisode(row.episode.id) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PodcastArtwork(
                        artworkUrl = row.episode.artworkUrl ?: row.episode.podcastArtworkUrl,
                        modifier = Modifier.size(EpisodeArtworkSize)
                    )
                    Column(
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            row.episode.podcastTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            row.episode.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            row.statusLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.play(row.episode.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${row.episode.title}")
                    }
                    // The same menu the other lists use, rather than a bare icon. Removing a
                    // download was previously a tap on an unlabelled check mark, which gave no clue
                    // that it was the delete control at all.
                    EpisodeActionsMenu(
                        episodeTitle = row.episode.title,
                        isPlayed = row.episode.isPlayed,
                        download = row.download,
                        onEnqueue = { viewModel.enqueue(row.episode.id) },
                        onDownload = { viewModel.retry(row.episode.id) },
                        onRemoveDownload = { viewModel.remove(row.episode.id) },
                        onTogglePlayed = { viewModel.togglePlayed(row.episode) }
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

/** Size once it is settled, progress while it isn't - whichever is the useful number right now. */
private fun DownloadsViewModel.Row.statusLabel(): String = when (download.status) {
    DownloadStatus.DOWNLOADED -> formatBytes(download.bytesDownloaded)
    DownloadStatus.DOWNLOADING -> "Downloading ${download.percent.toInt()}% · ${formatBytes(download.bytesDownloaded)}"
    DownloadStatus.QUEUED -> "Waiting"
    DownloadStatus.FAILED -> "Failed - tap to retry"
    DownloadStatus.REMOVING -> "Deleting"
}
