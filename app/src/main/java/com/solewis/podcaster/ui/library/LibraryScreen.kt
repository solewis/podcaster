package com.solewis.podcaster.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.ui.common.UnsubscribeConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onOpenShow: (Long) -> Unit) {
    val podcasts by viewModel.podcasts.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    var pendingUnsubscribe by remember { mutableStateOf<PodcastEntity?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Library") }) }) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::refreshAll,
            modifier = Modifier.padding(innerPadding)
        ) {
            if (podcasts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No shows yet - search to subscribe to one.", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(podcasts, key = { it.id }) { podcast ->
                        LibraryRow(
                            podcast = podcast,
                            onClick = { onOpenShow(podcast.id) },
                            onUnsubscribe = { pendingUnsubscribe = podcast }
                        )
                    }
                }
            }
        }
    }

    pendingUnsubscribe?.let { podcast ->
        UnsubscribeConfirmDialog(
            podcastTitle = podcast.title,
            onConfirm = {
                viewModel.unsubscribe(podcast.id)
                pendingUnsubscribe = null
            },
            onDismiss = { pendingUnsubscribe = null }
        )
    }
}


@Composable
private fun LibraryRow(podcast: PodcastEntity, onClick: () -> Unit, onUnsubscribe: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = podcast.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(podcast.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            podcast.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        IconButton(onClick = onUnsubscribe) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Unsubscribe from ${podcast.title}")
        }
    }
}
