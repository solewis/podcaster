package com.solewis.podcaster.ui.show

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.EpisodeDetailSheet
import com.solewis.podcaster.ui.common.EpisodeDetailUi
import com.solewis.podcaster.ui.common.PodcastArtwork
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
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var highlightedEpisodeId by remember { mutableStateOf<String?>(null) }
    var isJumpTargetVisible by remember { mutableStateOf(false) }
    var selectedEpisode by remember { mutableStateOf<EpisodeListItem?>(null) }
    var selectedEpisodeDescription by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selectedEpisode?.id) {
        selectedEpisodeDescription = null
        selectedEpisode?.let { selectedEpisodeDescription = viewModel.descriptionFor(it.id) }
    }

    LaunchedEffect(refreshError) {
        refreshError?.let { snackbarHostState.showSnackbar(it) }
    }

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
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        BackButtonRow(onBack)
        Box(modifier = Modifier.weight(1f)) {
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
                            is ShowListItem.Header -> ShowHeader(
                                header = listItem,
                                isRefreshing = isRefreshing,
                                onRefresh = viewModel::refresh,
                                onToggleSortOrder = viewModel::toggleSortOrder
                            )
                            is ShowListItem.Episode -> {
                                EpisodeRow(
                                    episode = listItem.item,
                                    isHighlighted = listItem.item.id == highlightedEpisodeId,
                                    onClick = { selectedEpisode = listItem.item },
                                    onPlay = { viewModel.play(listItem.item.id) },
                                    onEnqueue = { viewModel.enqueue(listItem.item.id) }
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

    selectedEpisode?.let { episode ->
        val numberLabel = episode.displayNumber?.let { "Ep $it" }
            ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
        EpisodeDetailSheet(
            episode = EpisodeDetailUi(
                title = episode.title,
                numberLabel = numberLabel,
                dateLabel = formatEpisodeDate(episode.pubDateMillis),
                durationLabel = formatDuration(episode.durationMillis),
                description = selectedEpisodeDescription,
                isPlayed = episode.isPlayed
            ),
            onPlay = { viewModel.play(episode.id) },
            onDismiss = { selectedEpisode = null }
        )
    }
}

/**
 * Name, episode count, and artwork up top; the sort/refresh controls in the slot a preview
 * screen's Subscribe button would occupy; then a divider, the show's own description (if the
 * feed has one), another divider, and an "Episodes" label - mirroring
 * [com.solewis.podcaster.ui.showpreview.ShowPreviewScreen]'s header shape, since it's the same
 * "podcast detail" page whether or not you're subscribed yet.
 */
@Composable
private fun ShowHeader(
    header: ShowListItem.Header,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onToggleSortOrder: () -> Unit
) {
    val podcast = header.podcast
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            podcast.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        podcast.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            "${header.episodeCount} episodes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        PodcastArtwork(
            artworkUrl = podcast.artworkUrl,
            modifier = Modifier.size(160.dp),
            shape = MaterialTheme.shapes.extraLarge
        )
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleSortOrder) {
                Text(if (podcast.sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first")
            }
            if (isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(horizontal = 12.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Check for new episodes")
                }
            }
        }
    }

    HorizontalDivider()
    podcast.description?.takeIf(String::isNotBlank)?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)
        )
        HorizontalDivider()
    }
    Text(
        "Episodes",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
    )
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
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit
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
            .clickable(onClick = onClick)
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
            Row {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
                }
                IconButton(onClick = onEnqueue) {
                    Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add ${episode.title} to queue")
                }
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
