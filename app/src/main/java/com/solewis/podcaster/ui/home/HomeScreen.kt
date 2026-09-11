package com.solewis.podcaster.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.ui.common.EmptyState
import com.solewis.podcaster.ui.common.EpisodeActionRow
import com.solewis.podcaster.ui.common.EpisodeArtworkSize
import com.solewis.podcaster.ui.common.EpisodeDescriptionPreview
import com.solewis.podcaster.ui.common.EpisodeMetaAndProgressRow
import com.solewis.podcaster.ui.common.downloadStatusLabel
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.episodeProgressUi
import com.solewis.podcaster.ui.common.ScreenTitle
import com.solewis.podcaster.ui.common.TestTags

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenShow: (Long) -> Unit,
    onOpenEpisode: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding).statusBarsPadding()) {
            // Settings hangs off the first screen you land on rather than taking a fourth tab: it
            // is somewhere you go once and then forget about, which is the opposite of a tab.
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Library")
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(end = 12.dp).testTag(TestTags.SETTINGS_BUTTON)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
            if (state.isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.subscriptions.isEmpty()) {
                EmptyState("No shows yet - search to subscribe to one.", Modifier.weight(1f))
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
                            onEnqueue = { viewModel.enqueue(episode) },
                            download = downloadStates[episode.id],
                            onDownload = { viewModel.download(episode.id) },
                            onRemoveDownload = { viewModel.removeDownload(episode.id) },
                            onTogglePlayed = { viewModel.togglePlayed(episode) }
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
    onEnqueue: () -> Unit,
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onTogglePlayed: () -> Unit
) {
    Column(
        // Tappable to open episode details; the trailing controls keep their own click targets,
        // matching the nested-clickable pattern used elsewhere (e.g. MiniPlayer).
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row {
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
            }
        }

        EpisodeDescriptionPreview(episode.descriptionPreview)

        val progress = episodeProgressUi(
            pubDateMillis = episode.pubDateMillis,
            durationMillis = episode.durationMillis,
            positionMillis = episode.positionMillis,
            isPlayed = episode.isPlayed,
            livePositionMillis = nowPlayingPositionMillis.takeIf { isNowPlaying },
            liveDurationMillis = nowPlayingDurationMillis.takeIf { isNowPlaying }
        )
        val label = listOfNotNull(
            progress.label.takeIf { it.isNotEmpty() },
            downloadStatusLabel(download)
        ).joinToString(" · ")
        EpisodeMetaAndProgressRow(progress = progress.copy(label = label), isPlayed = episode.isPlayed)

        EpisodeActionRow(
            episodeTitle = episode.title,
            isPlaying = isNowPlaying,
            isLoading = isLoading,
            onPlayOrToggle = onPlayOrToggle,
            onEnqueue = onEnqueue,
            download = download,
            onDownload = onDownload,
            onRemoveDownload = onRemoveDownload,
            isPlayed = episode.isPlayed,
            onTogglePlayed = onTogglePlayed
        )
    }
}
