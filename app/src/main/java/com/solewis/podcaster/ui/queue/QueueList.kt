package com.solewis.podcaster.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.QueueItem
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.formatDuration

/** The "Queue" segment of the Activity tab. Content only, no Scaffold/TopAppBar of its own -
 * [com.solewis.podcaster.ui.activity.ActivityScreen] owns those. */
@Composable
fun QueueList(viewModel: QueueViewModel, modifier: Modifier = Modifier) {
    val items by viewModel.items.collectAsState()

    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Your queue is empty - add episodes from a show's episode list.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    } else {
        LazyColumn(modifier = modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(items) { index, item ->
                QueueRow(
                    item = item,
                    isFirst = index == 0,
                    isLast = index == items.lastIndex,
                    onPlayNow = { viewModel.playNow(item) },
                    onMoveUp = { viewModel.moveUp(item.queueId) },
                    onMoveDown = { viewModel.moveDown(item.queueId) },
                    onRemove = { viewModel.remove(item.queueId) }
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    isFirst: Boolean,
    isLast: Boolean,
    onPlayNow: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PodcastArtwork(
            artworkUrl = item.artworkUrl ?: item.podcastArtworkUrl,
            modifier = Modifier.size(56.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item.podcastTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            formatDuration(item.durationMillis)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = onMoveUp, enabled = !isFirst) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
        }
        IconButton(onClick = onMoveDown, enabled = !isLast) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
        }
        IconButton(onClick = onPlayNow) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play ${item.title} now")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "Remove ${item.title} from queue")
        }
    }
}
