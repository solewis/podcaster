package com.solewis.podcaster.ui.streamcache

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.ui.common.DetailTopBar
import com.solewis.podcaster.ui.common.EmptyState
import com.solewis.podcaster.ui.common.EpisodeArtworkSize
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.TestTags
import com.solewis.podcaster.ui.common.formatBytes
import com.solewis.podcaster.ui.common.formatEpisodeDate

/**
 * What specifically is filling up the rolling streaming cache - see [StreamCacheViewModel]'s doc
 * comment for why a size total alone is not enough to answer that.
 */
@Composable
fun StreamCacheScreen(
    viewModel: StreamCacheViewModel,
    onBack: () -> Unit,
    onOpenEpisode: (String) -> Unit
) {
    val rows by viewModel.rows.collectAsState()
    val totalBytes by viewModel.totalBytes.collectAsState()

    Scaffold(
        modifier = Modifier.testTag(TestTags.STREAM_CACHE_SCREEN),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        // The bar carries the status-bar padding this screen was missing entirely, which had left
        // the back button drawn up inside the phone's status bar. Pinned for the same reason as
        // Settings: the list below it is as long as the cache is full.
        topBar = { DetailTopBar("Streaming cache", onBack) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (rows.isEmpty()) {
                EmptyState("Nothing cached right now.", modifier = Modifier.fillMaxSize())
                return@Scaffold
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${rows.size} ${if (rows.size == 1) "episode" else "episodes"} · ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = viewModel::clearAll, modifier = Modifier.testTag(TestTags.CLEAR_ALL_CACHED_EPISODES)) {
                    Text("Clear all")
                }
            }
            HorizontalDivider()
            LazyColumn {
                items(rows, key = { it.entry.episodeId }) { row ->
                    val episode = row.episode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { base -> if (episode != null) base.clickable { onOpenEpisode(episode.id) } else base }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastArtwork(
                            artworkUrl = episode?.artworkUrl ?: episode?.podcastArtworkUrl,
                            modifier = Modifier.size(EpisodeArtworkSize)
                        )
                        Column(
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (episode != null) {
                                Text(
                                    episode.podcastTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(episode.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            } else {
                                // A show that has since been unsubscribed leaves its bytes behind
                                // with nothing left to name it by - exactly the kind of stray entry
                                // this screen exists to surface, not hide.
                                Text(
                                    "Unknown episode",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                buildString {
                                    append(formatBytes(row.entry.sizeBytes))
                                    formatEpisodeDate(row.entry.lastTouchedAtMillis)?.let { append(" · cached $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { viewModel.remove(row.entry.episodeId) },
                            modifier = Modifier.testTag(TestTags.removeCachedEpisode(row.entry.episodeId))
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove ${episode?.title ?: "this episode"} from the cache")
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
