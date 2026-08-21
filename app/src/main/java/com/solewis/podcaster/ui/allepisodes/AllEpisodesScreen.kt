package com.solewis.podcaster.ui.allepisodes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.formatDuration
import com.solewis.podcaster.ui.common.formatEpisodeDate

/** Every episode across every subscription, newest-published first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllEpisodesScreen(viewModel: AllEpisodesViewModel) {
    val episodes by viewModel.episodes.collectAsState()

    Scaffold(topBar = { TopAppBar(title = { Text("All Episodes") }) }) { innerPadding ->
        when {
            episodes.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text("No episodes yet - subscribe to a show to see them here.")
            }
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(episodes, key = { it.id }) { episode ->
                    FeedEpisodeRow(
                        episode = episode,
                        onPlay = { viewModel.play(episode) },
                        onEnqueue = { viewModel.enqueue(episode) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FeedEpisodeRow(episode: EpisodeFeedItem, onPlay: () -> Unit, onEnqueue: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        PodcastArtwork(
            artworkUrl = episode.artworkUrl ?: episode.podcastArtworkUrl,
            modifier = Modifier.size(56.dp)
        )

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                episode.podcastTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Text(episode.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))

            val dateAndDuration = listOfNotNull(
                formatEpisodeDate(episode.pubDateMillis),
                formatDuration(episode.durationMillis)
            ).joinToString(" · ")
            if (dateAndDuration.isNotEmpty()) {
                Text(
                    dateAndDuration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            val progressText = when {
                episode.isPlayed -> null
                episode.positionMillis > 0 && episode.durationMillis != null ->
                    "${formatDuration(episode.positionMillis)} / ${formatDuration(episode.durationMillis)}"
                else -> null
            }
            progressText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
                }
                IconButton(onClick = onEnqueue) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add ${episode.title} to queue")
                }
            }
            if (episode.isPlayed) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Played",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
