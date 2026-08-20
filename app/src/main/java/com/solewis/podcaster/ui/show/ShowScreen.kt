package com.solewis.podcaster.ui.show

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.ui.common.formatDuration
import com.solewis.podcaster.ui.common.formatEpisodeDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(viewModel: ShowViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var highlightedEpisodeId by remember { mutableStateOf<String?>(null) }
    var isJumpTargetVisible by remember { mutableStateOf(false) }

    val jump = state.jump
    LaunchedEffect(listState, jump?.episodeId) {
        if (jump == null) {
            isJumpTargetVisible = false
        } else {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == jump.episodeId } }
                .distinctUntilChanged()
                .collect { visible -> isJumpTargetVisible = visible }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.podcast?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    state.podcast?.let { podcast ->
                        TextButton(onClick = viewModel::toggleSortOrder) {
                            Text(if (podcast.sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(state.items, key = { it.key }) { listItem ->
                        when (listItem) {
                            is ShowListItem.Header -> ShowHeader(listItem)
                            is ShowListItem.Episode -> {
                                EpisodeRow(
                                    episode = listItem.item,
                                    isHighlighted = listItem.item.id == highlightedEpisodeId,
                                    onPlay = { viewModel.play(listItem.item.id) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            if (jump != null && !isJumpTargetVisible) {
                JumpToLastListenedPill(
                    jump = jump,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    onClick = {
                        scope.launch {
                            val distance = abs(jump.itemIndex - listState.firstVisibleItemIndex)
                            if (distance > 40) {
                                listState.scrollToItem(jump.itemIndex)
                            } else {
                                listState.animateScrollToItem(jump.itemIndex)
                            }
                            // Breathing room so the target row isn't flush against the top bar.
                            listState.animateScrollBy(-80f)

                            highlightedEpisodeId = jump.episodeId
                            delay(1200)
                            highlightedEpisodeId = null
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ShowHeader(header: ShowListItem.Header) {
    val podcast = header.podcast
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
                "${header.episodeCount} episodes",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun JumpToLastListenedPill(jump: JumpPillUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(jump.label, style = MaterialTheme.typography.labelLarge)
            jump.secondary?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeListItem,
    isHighlighted: Boolean,
    onPlay: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (isHighlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        animationSpec = tween(durationMillis = 400),
        label = "episodeHighlight"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(
                if (isHighlighted) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (isHighlighted) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
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
            Text(episode.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))

            val progressText = when {
                episode.isPlayed -> null
                episode.positionMillis > 0 && episode.durationMillis != null ->
                    "${formatDuration(episode.positionMillis)} / ${formatDuration(episode.durationMillis)}"
                else -> formatDuration(episode.durationMillis)
            }
            progressText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
            }
            if (episode.isPlayed) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Played",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
