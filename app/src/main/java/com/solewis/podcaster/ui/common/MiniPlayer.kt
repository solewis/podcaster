package com.solewis.podcaster.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.player.PlaybackUiState
import com.solewis.podcaster.player.ProgressUiState

/**
 * Persistent playback bar. Lives in `PodcasterRoot`'s `Scaffold.bottomBar`, outside the `NavHost`,
 * so it survives navigation and never re-subscribes to [PlaybackUiState] on a screen change.
 */
@Composable
fun MiniPlayer(
    playback: PlaybackUiState,
    progress: ProgressUiState,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit
) {
    if (playback.episodeId == null) return

    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onExpand)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    playback.title?.let {
                        Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    playback.podcastTitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play"
                    )
                }
            }

            // Flush along the bottom edge, so the bar reads as belonging to the whole panel
            // rather than being one more element inside it.
            progress.durationMillis?.let { duration ->
                EpisodeProgressBar(positionMillis = progress.positionMillis, durationMillis = duration)
            }
        }
    }
}
