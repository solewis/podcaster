package com.solewis.podcaster.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag

/**
 * Persistent playback bar. Lives in `PodcasterRoot`'s `Scaffold.bottomBar`, outside the `NavHost`,
 * so it survives navigation and never re-subscribes to [PlaybackUiState] on a screen change.
 */
@Composable
fun MiniPlayer(
    playback: PlaybackUiState,
    progress: ProgressUiState,
    /** Waiting on data for long enough to say so - see [com.solewis.podcaster.player.Playback.isStalled]. */
    isStalled: Boolean,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit
) {
    if (playback.episodeId == null) return

    // A tint rather than tonalElevation: elevation blends surfaceTint into the surface, which over
    // a warm cream background lands on a washed-out grey with barely any edge to it - about 1.06:1,
    // effectively invisible. The primary container is the same accent the rest of the app uses and
    // gives the bar a real boundary in both themes.
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            modifier = Modifier
                .testTag(TestTags.MINI_PLAYER)
                .fillMaxWidth()
                .clickable(onClick = onExpand)
        ) {
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
                    if (isStalled) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).testTag(TestTags.MINI_PLAYER_SPINNER),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            // See NowPlayingScreen: intent rather than audibility, so a seek does
                            // not flicker the icon.
                            if (playback.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playback.playWhenReady) "Pause" else "Play"
                        )
                    }
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
