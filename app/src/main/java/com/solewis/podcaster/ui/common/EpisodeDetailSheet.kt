package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What the sheet needs to render - deliberately decoupled from where the episode came from
 * (Room-backed [com.solewis.podcaster.data.db.model.EpisodeListItem]/[com.solewis.podcaster.data.db.model.EpisodeFeedItem]
 * for a subscribed show, or a live-fetched [com.solewis.podcaster.domain.FeedToEpisodesMapper.MappedEpisode]
 * for an unsubscribed preview) so this one composable serves both.
 */
data class EpisodeDetailUi(
    val title: String,
    val numberLabel: String?,
    val dateLabel: String?,
    val durationLabel: String?,
    val description: String?,
    val isPlayed: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeDetailSheet(episode: EpisodeDetailUi, onPlay: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
        ) {
            Row {
                episode.numberLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("  ", style = MaterialTheme.typography.labelLarge)
                }
                episode.dateLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(episode.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
            episode.durationLabel?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onPlay(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.height(0.dp))
                Text(if (episode.isPlayed) "Play again" else "Play", modifier = Modifier.padding(start = 8.dp))
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(
                episode.description ?: "No description available.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
