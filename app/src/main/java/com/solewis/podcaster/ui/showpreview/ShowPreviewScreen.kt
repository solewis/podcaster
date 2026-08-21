package com.solewis.podcaster.ui.showpreview

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.repo.ShowPreview
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.EpisodeDetailSheet
import com.solewis.podcaster.ui.common.EpisodeDetailUi
import com.solewis.podcaster.ui.common.formatDuration
import com.solewis.podcaster.ui.common.formatEpisodeDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowPreviewScreen(
    viewModel: ShowPreviewViewModel,
    onBack: () -> Unit,
    onSubscribed: (podcastId: Long) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedEpisode by remember { mutableStateOf<FeedToEpisodesMapper.MappedEpisode?>(null) }

    LaunchedEffect(state.subscribedPodcastId) {
        state.subscribedPodcastId?.let(onSubscribed)
    }

    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val preview = state.preview

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column {
                    BackButtonRow(onBack)
                    preview?.let {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                            Text(
                                it.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            it.author?.let { author ->
                                Text(
                                    author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            when {
                state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
                preview != null -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Button(onClick = viewModel::subscribe, enabled = !state.isSubscribing) {
                            if (state.isSubscribing) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                            Text("Subscribe", modifier = Modifier.padding(start = 4.dp))
                        }
                    }

                    SecondaryTabRow(selectedTabIndex = selectedTab) {
                        Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Episodes") })
                        Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("About") })
                    }

                    when (selectedTab) {
                        0 -> LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                            items(preview.episodes) { episode ->
                                PreviewEpisodeRow(
                                    episode = episode,
                                    onClick = { selectedEpisode = episode },
                                    onPlay = { viewModel.play(episode) }
                                )
                                HorizontalDivider()
                            }
                        }
                        else -> AboutTab(preview = preview, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }

    selectedEpisode?.let { episode ->
        val numberLabel = episode.displayNumber?.let { "Ep $it" }
            ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
        EpisodeDetailSheet(
            episode = EpisodeDetailUi(
                title = episode.title,
                numberLabel = numberLabel,
                dateLabel = formatEpisodeDate(episode.pubDateMillis),
                durationLabel = formatDuration(episode.durationMillis),
                description = viewModel.descriptionFor(episode),
                isPlayed = false
            ),
            onPlay = { viewModel.play(episode) },
            onDismiss = { selectedEpisode = null }
        )
    }
}

@Composable
private fun AboutTab(preview: ShowPreview, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "${preview.episodes.size} episodes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            preview.description?.takeIf(String::isNotBlank) ?: "No description available.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun PreviewEpisodeRow(
    episode: FeedToEpisodesMapper.MappedEpisode,
    onClick: () -> Unit,
    onPlay: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            // Tappable to open episode details; only the play icon has its own click target,
            // matching the nested-clickable pattern used elsewhere (e.g. MiniPlayer).
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
        ) {
            val numberLabel = episode.displayNumber?.let { "Ep $it" }
                ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
            Row {
                numberLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text("  ", style = MaterialTheme.typography.labelMedium)
                }
                formatEpisodeDate(episode.pubDateMillis)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            formatDuration(episode.durationMillis)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        IconButton(onClick = onPlay) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
        }
    }
}
