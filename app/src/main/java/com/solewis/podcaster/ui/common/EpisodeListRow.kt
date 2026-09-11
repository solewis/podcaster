package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.repo.EpisodeDownload

/**
 * The short excerpt under an episode's title on a list row - a preview, not the full show notes.
 * Truncated to two lines rather than measured against [com.solewis.podcaster.data.repo.SubscriptionRepository.DESCRIPTION_PREVIEW_LENGTH]:
 * that constant bounds what got stored, this bounds what's drawn, and the two are allowed to
 * disagree (a short screen at a large font could still wrap before the stored text runs out).
 *
 * Nothing is drawn at all when there's no preview - an untruncated older row (predating the column
 * that stores this) or a feed with no description - rather than an empty two-line gap.
 */
@Composable
fun EpisodeDescriptionPreview(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        // More muted than the meta line below it: this is flavour text you can skim past, not
        // information (a date, a duration, a played state) someone opened the list to read.
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * The metadata line and the progress bar, side by side rather than stacked - reported feedback on
 * the previous layout, where the bar sat on its own line below the time and read as a second,
 * separate piece of information rather than as the same fact drawn two ways.
 *
 * The label takes only the width its text needs (`weight(1f, fill = false)`) so it does not force
 * a wrap; the bar takes whatever is left, which is enough even at the widest labels this app
 * produces ("Sep 3 · 42m left · Downloading 42%") on a phone-width row.
 */
@Composable
fun EpisodeMetaAndProgressRow(
    progress: EpisodeProgressUi,
    isPlayed: Boolean,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EpisodeMetaLine(
            label = progress.label,
            isPlayed = isPlayed,
            color = color,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (progress.showBar) {
            EpisodeProgressBar(
                positionMillis = progress.positionMillis!!,
                durationMillis = progress.durationMillis!!,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
        }
    }
}

/**
 * Play, add to queue, download, and everything else - the four controls a list row offers, in one
 * place so the Home feed and a show's episode list can't drift apart on what a row can do.
 *
 * Add to queue and download are now standalone buttons rather than menu items, which is why both
 * are turned off inside [EpisodeActionsMenu] here: offering the same action from a button and from
 * the menu it sits next to invites the reading that the two do something different.
 */
@Composable
fun EpisodeActionRow(
    episodeTitle: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayOrToggle: () -> Unit,
    onEnqueue: () -> Unit,
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    isPlayed: Boolean,
    onTogglePlayed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPlayOrToggle, enabled = !isLoading) {
            when {
                // A spinner in the button's own place, not beside it, so the row does not reflow -
                // the wait between tapping play and hearing anything (controller connection, then
                // buffering) is real and used to look like nothing had happened.
                isLoading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                isPlaying -> Icon(Icons.Default.Pause, contentDescription = "Pause $episodeTitle")
                else -> Icon(Icons.Default.PlayArrow, contentDescription = "Play $episodeTitle")
            }
        }
        IconButton(
            onClick = onEnqueue,
            modifier = Modifier.testTag(TestTags.enqueueButton(episodeTitle))
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add $episodeTitle to queue")
        }
        DownloadButton(
            episodeTitle = episodeTitle,
            download = download,
            onDownload = onDownload,
            onRemove = onRemoveDownload
        )
        EpisodeActionsMenu(
            episodeTitle = episodeTitle,
            isPlayed = isPlayed,
            download = download,
            onEnqueue = onEnqueue,
            onDownload = onDownload,
            onRemoveDownload = onRemoveDownload,
            onTogglePlayed = onTogglePlayed,
            includeDownload = false,
            includeEnqueue = false
        )
    }
}
