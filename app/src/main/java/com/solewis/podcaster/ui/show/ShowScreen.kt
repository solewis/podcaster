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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.domain.JumpTargetResolver
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.UnsubscribeConfirmDialog
import com.solewis.podcaster.ui.common.formatDuration
import com.solewis.podcaster.ui.common.formatEpisodeDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(viewModel: ShowViewModel, onBack: () -> Unit, onOpenEpisode: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val didUnsubscribe by viewModel.didUnsubscribe.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var highlightedEpisodeId by remember { mutableStateOf<String?>(null) }
    var isJumpTargetVisible by remember { mutableStateOf(false) }
    var pendingUnsubscribe by remember { mutableStateOf(false) }

    LaunchedEffect(refreshError) {
        refreshError?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(didUnsubscribe) {
        if (didUnsubscribe) onBack()
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
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        // Deliberately no statusBarsPadding on this outer Column - the banner Surface below
        // needs to paint its color all the way to the true top of the screen, under the status
        // bar. statusBarsPadding is applied just inside the Surface instead, so only the back
        // button/title/artwork/subscribe content (not the color itself) is inset from it.
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val podcast = state.podcast

            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    BackButtonRow(onBack)
                    podcast?.let {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    it.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                it.author?.let { author ->
                                    Text(
                                        author,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            PodcastArtwork(artworkUrl = it.artworkUrl, modifier = Modifier.size(56.dp))
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                            OutlinedButton(onClick = { pendingUnsubscribe = true }) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Subscribed", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                }
            }

            if (podcast != null) {
                SecondaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Episodes") })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("About") })
                }

                when (selectedTab) {
                    0 -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = viewModel::toggleSortOrder) {
                                    Text(if (podcast.sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first")
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(horizontal = 12.dp))
                                } else {
                                    IconButton(onClick = viewModel::refresh) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Check for new episodes")
                                    }
                                }
                            }
                            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                                items(state.episodes, key = { it.id }) { episode ->
                                    EpisodeRow(
                                        episode = episode,
                                        isHighlighted = episode.id == highlightedEpisodeId,
                                        onClick = { onOpenEpisode(episode.id) },
                                        onPlay = { viewModel.play(episode.id) },
                                        onEnqueue = { viewModel.enqueue(episode.id) }
                                    )
                                    HorizontalDivider()
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
                    else -> AboutTab(podcast = podcast, episodeCount = state.episodes.size)
                }
            } else if (state.isLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (pendingUnsubscribe) {
        state.podcast?.let { podcast ->
            UnsubscribeConfirmDialog(
                podcastTitle = podcast.title,
                onConfirm = {
                    viewModel.unsubscribe()
                    pendingUnsubscribe = false
                },
                onDismiss = { pendingUnsubscribe = false }
            )
        }
    }
}

@Composable
private fun AboutTab(podcast: PodcastEntity, episodeCount: Int) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "$episodeCount episodes",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            podcast.description?.takeIf(String::isNotBlank) ?: "No description available.",
            style = MaterialTheme.typography.bodyMedium
        )
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
            Text(
                episode.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )

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
