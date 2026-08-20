package com.solewis.podcaster.ui.search

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.solewis.podcaster.data.repo.PodcastSearchResult
import com.solewis.podcaster.ui.common.UnsubscribeConfirmDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: SearchViewModel, onOpenShow: (PodcastSearchResult) -> Unit) {
    val state by viewModel.state.collectAsState()
    var pendingUnsubscribe by remember { mutableStateOf<PodcastSearchResult?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Search") }) }) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search podcasts") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )

            when {
                state.isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error ?: "", color = MaterialTheme.colorScheme.error)
                }
                state.results.isEmpty() && state.query.isNotBlank() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No shows found")
                    }
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    // No custom key: iTunes Search can return two different catalog entries
                    // (different collectionIds) that point at the identical feedUrl - crashed
                    // with "Key ... was already used" on a real search before this was found.
                    // The list is fully replaced on every query anyway, so the default
                    // positional key is fine.
                    items(state.results) { result ->
                        SearchResultRow(
                            result = result,
                            isSubscribing = state.subscribingFeedUrl == result.feedUrl,
                            isSubscribed = result.feedUrl in state.subscribedFeedUrls,
                            onClick = { onOpenShow(result) },
                            onSubscribe = { viewModel.subscribe(result) },
                            onUnsubscribe = { pendingUnsubscribe = result }
                        )
                    }
                }
            }
        }
    }

    pendingUnsubscribe?.let { result ->
        UnsubscribeConfirmDialog(
            podcastTitle = result.title,
            onConfirm = {
                viewModel.unsubscribe(result.feedUrl)
                pendingUnsubscribe = null
            },
            onDismiss = { pendingUnsubscribe = null }
        )
    }
}

@Composable
private fun SearchResultRow(
    result: PodcastSearchResult,
    isSubscribing: Boolean,
    isSubscribed: Boolean,
    onClick: () -> Unit,
    onSubscribe: () -> Unit,
    onUnsubscribe: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = result.artworkUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            result.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        when {
            isSubscribing -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            isSubscribed -> IconButton(onClick = onUnsubscribe) {
                Icon(Icons.Default.Check, contentDescription = "Subscribed - tap to unsubscribe", tint = MaterialTheme.colorScheme.primary)
            }
            else -> TextButton(onClick = onSubscribe) { Text("Subscribe") }
        }
    }
}
