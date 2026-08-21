package com.solewis.podcaster.ui.showpreview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.ui.common.EpisodeDetailSheet
import com.solewis.podcaster.ui.common.EpisodeDetailUi
import com.solewis.podcaster.ui.common.PodcastArtwork
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
    var selectedEpisode by remember { mutableStateOf<FeedToEpisodesMapper.MappedEpisode?>(null) }

    LaunchedEffect(state.subscribedPodcastId) {
        state.subscribedPodcastId?.let(onSubscribed)
    }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
                state.preview != null -> {
                    val preview = state.preview!!
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        item {
                            PreviewHeader(
                                onBack = onBack,
                                title = preview.title,
                                author = preview.author,
                                episodeCount = preview.episodes.size,
                                description = preview.description,
                                artworkUrl = preview.artworkUrl,
                                isSubscribing = state.isSubscribing,
                                isSubscribed = state.subscribedPodcastId != null,
                                onSubscribe = viewModel::subscribe
                            )
                        }
                        items(preview.episodes) { episode ->
                            PreviewEpisodeRow(
                                episode = episode,
                                onClick = { selectedEpisode = episode },
                                onPlay = { viewModel.play(episode) }
                            )
                            HorizontalDivider()
                        }
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

/** Mirrors [com.solewis.podcaster.ui.show.ShowScreen]'s header shape: back button inline with
 * the title/artwork row (not its own bar), a Subscribe row below (that screen has sort/refresh
 * in the equivalent slot instead, once you're already subscribed), then the description and an
 * "Episodes" label - all sharing the same left inset as the episode rows below. */
@Composable
private fun PreviewHeader(
    onBack: () -> Unit,
    title: String,
    author: String?,
    episodeCount: Int,
    description: String?,
    artworkUrl: String?,
    isSubscribing: Boolean,
    isSubscribed: Boolean,
    onSubscribe: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                author?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "$episodeCount episodes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            PodcastArtwork(
                artworkUrl = artworkUrl,
                modifier = Modifier.size(64.dp),
                shape = MaterialTheme.shapes.medium
            )
        }

        Button(
            onClick = onSubscribe,
            enabled = !isSubscribing && !isSubscribed,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                isSubscribing -> CircularProgressIndicator(modifier = Modifier.size(18.dp))
                isSubscribed -> {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Text(" Subscribed", modifier = Modifier.padding(start = 4.dp))
                }
                else -> Text("Subscribe")
            }
        }

        description?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        Text(
            "Episodes",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        HorizontalDivider()
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
