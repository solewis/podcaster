package com.solewis.podcaster.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.ui.common.EpisodeProgressBar
import com.solewis.podcaster.ui.common.EpisodeArtworkSize
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.episodeProgressUi
import com.solewis.podcaster.ui.common.ScreenTitle

@Composable
fun HomeScreen(viewModel: HomeViewModel, onOpenShow: (Long) -> Unit, onOpenEpisode: (String) -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).statusBarsPadding()) {
            ScreenTitle("Library")
            if (state.isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.subscriptions.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No shows yet - search to subscribe to one.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        SubscriptionsRow(subscriptions = state.subscriptions, onOpenShow = onOpenShow)
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    }
                    items(state.episodes, key = { it.id }) { episode ->
                        val isNowPlaying = state.nowPlayingEpisodeId == episode.id
                        FeedEpisodeRow(
                            episode = episode,
                            isLoading = state.loadingEpisodeId == episode.id,
                            isNowPlaying = isNowPlaying,
                            nowPlayingPositionMillis = state.nowPlayingPositionMillis,
                            nowPlayingDurationMillis = state.nowPlayingDurationMillis,
                            onClick = { onOpenEpisode(episode.id) },
                            onPlayOrToggle = { if (isNowPlaying) viewModel.togglePlayPause() else viewModel.play(episode) },
                            onEnqueue = { viewModel.enqueue(episode) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionsRow(subscriptions: List<HomeShowSummary>, onOpenShow: (Long) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(subscriptions, key = { it.id }) { show ->
            PodcastArtwork(
                artworkUrl = show.artworkUrl,
                modifier = Modifier
                    .size(72.dp)
                    .clickable { onOpenShow(show.id) }
            )
        }
    }
}

@Composable
private fun FeedEpisodeRow(
    episode: EpisodeFeedItem,
    isLoading: Boolean,
    isNowPlaying: Boolean,
    nowPlayingPositionMillis: Long,
    nowPlayingDurationMillis: Long?,
    onClick: () -> Unit,
    onPlayOrToggle: () -> Unit,
    onEnqueue: () -> Unit
) {
    Row(
        // Tappable to open episode details; the play/enqueue icons keep their own click targets,
        // matching the nested-clickable pattern used elsewhere (e.g. MiniPlayer).
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        PodcastArtwork(
            artworkUrl = episode.artworkUrl ?: episode.podcastArtworkUrl,
            modifier = Modifier.size(EpisodeArtworkSize)
        )

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                episode.podcastTitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
            Text(
                episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

            val progress = episodeProgressUi(
                pubDateMillis = episode.pubDateMillis,
                durationMillis = episode.durationMillis,
                positionMillis = episode.positionMillis,
                isPlayed = episode.isPlayed,
                livePositionMillis = nowPlayingPositionMillis.takeIf { isNowPlaying },
                liveDurationMillis = nowPlayingDurationMillis.takeIf { isNowPlaying }
            )
            if (progress.label.isNotEmpty()) {
                Text(
                    progress.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (progress.showBar) {
                EpisodeProgressBar(
                    positionMillis = progress.positionMillis!!,
                    durationMillis = progress.durationMillis!!,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                IconButton(onClick = onPlayOrToggle, enabled = !isLoading) {
                    when {
                        isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        isNowPlaying -> Icon(Icons.Default.Pause, contentDescription = "Pause ${episode.title}")
                        else -> Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
                    }
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
