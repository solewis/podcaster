package com.solewis.podcaster.ui.show

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.ui.common.formatDuration
import com.solewis.podcaster.ui.common.formatEpisodeDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(viewModel: ShowViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.podcast?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            else -> LazyColumn(
                modifier = Modifier.padding(innerPadding),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                item { ShowHeader(state) }
                items(state.episodes, key = { it.id }) { episode ->
                    EpisodeRow(episode)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ShowHeader(state: ShowViewModel.UiState) {
    val podcast = state.podcast ?: return
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
        AsyncImage(
            model = podcast.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(80.dp).clip(RoundedCornerShape(12.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(podcast.title, style = MaterialTheme.typography.titleLarge)
            podcast.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "${state.episodes.size} episodes",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun EpisodeRow(episode: EpisodeListItem) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val numberLabel = episode.displayNumber?.let { "Ep $it" }
                ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
            Row {
                numberLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text("  ", style = MaterialTheme.typography.labelMedium)
                }
                formatEpisodeDate(episode.pubDateMillis)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(episode.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
            formatDuration(episode.durationMillis)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (episode.isPlayed) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Played",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
