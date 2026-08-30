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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.ui.common.BackButtonRow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.solewis.podcaster.ui.common.EpisodeActionsMenu
import com.solewis.podcaster.ui.common.EpisodeProgressBar
import com.solewis.podcaster.ui.common.downloadStatusLabel
import com.solewis.podcaster.ui.common.EpisodeArtworkSize
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.SubscribeButton
import com.solewis.podcaster.ui.common.UnsubscribeConfirmDialog
import com.solewis.podcaster.ui.common.episodeProgressUi
import com.solewis.podcaster.ui.common.formatEpisodeDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.platform.testTag
import com.solewis.podcaster.ui.common.TestTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowScreen(viewModel: ShowViewModel, onBack: () -> Unit, onOpenEpisode: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshError by viewModel.refreshError.collectAsState()
    val downloadStates by viewModel.downloadStates.collectAsState()
    val pendingEpisodeId by viewModel.pendingEpisodeId.collectAsState()
    val nowPlaying by viewModel.nowPlaying.collectAsState()
    val nowPlayingId = nowPlaying.episodeId
    val livePosition = nowPlaying.positionMillis
    val liveDuration = nowPlaying.durationMillis
    val didUnsubscribe by viewModel.didUnsubscribe.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedTab by remember { mutableIntStateOf(0) }
    var highlightedEpisodeId by remember { mutableStateOf<String?>(null) }
    var pendingUnsubscribe by remember { mutableStateOf(false) }
    var showMenuOpen by remember { mutableStateOf(false) }

    val markedAllPlayed by viewModel.markedAllPlayed.collectAsState()
    LaunchedEffect(markedAllPlayed) {
        // Says what it did: an action that silently rewrites a few hundred rows is indistinguishable
        // from one that did nothing.
        markedAllPlayed?.let { count ->
            snackbarHostState.showSnackbar(
                if (count == 0) "Nothing left to mark" else "Marked $count ${if (count == 1) "episode" else "episodes"} as played"
            )
            viewModel.clearMarkedAllPlayed()
        }
    }

    LaunchedEffect(refreshError) {
        refreshError?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(didUnsubscribe) {
        if (didUnsubscribe) onBack()
    }

    val jump = state.jump
    // derivedStateOf (not a LaunchedEffect+snapshotFlow collector) so this reflects the list's
    // actual current layout on every recomposition - the effect-based version only updated once
    // the collector had a chance to run, which is why the pill stayed hidden until the user
    // scrolled even when the jump target was never on screen to begin with.
    val isJumpTargetVisible by remember(jump?.episodeId) {
        derivedStateOf {
            jump != null && listState.layoutInfo.visibleItemsInfo.any { it.key == jump.episodeId }
        }
    }

    Scaffold(
        modifier = Modifier.testTag(TestTags.SHOW_SCREEN),
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
                            PodcastArtwork(artworkUrl = it.artworkUrl, modifier = Modifier.size(72.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SubscribeButton(
                                isSubscribed = true,
                                isBusy = false,
                                onClick = { pendingUnsubscribe = true }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            // A menu rather than another button: per-show actions are only going to
                            // accumulate (a speed override, intro trimming), and the episode rows
                            // below have already run out of room for trailing controls.
                            Box {
                                IconButton(
                                    onClick = { showMenuOpen = true },
                                    modifier = Modifier.testTag(TestTags.SHOW_MENU)
                                ) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More actions for this show")
                                }
                                DropdownMenu(
                                    expanded = showMenuOpen,
                                    onDismissRequest = { showMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Mark all as played") },
                                        leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                                        onClick = {
                                            showMenuOpen = false
                                            viewModel.markAllPlayed()
                                        },
                                        modifier = Modifier.testTag(TestTags.MARK_ALL_PLAYED)
                                    )
                                }
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
                                        podcastArtworkUrl = podcast.artworkUrl,
                                        isHighlighted = episode.id == highlightedEpisodeId,
                                        onClick = { onOpenEpisode(episode.id) },
                                        isStarting = episode.id == pendingEpisodeId,
                                        livePositionMillis = livePosition.takeIf { episode.id == nowPlayingId },
                                        liveDurationMillis = liveDuration.takeIf { episode.id == nowPlayingId },
                                        onPlay = { viewModel.play(episode.id) },
                                        onEnqueue = { viewModel.enqueue(episode.id) },
                                        download = downloadStates[episode.id],
                                        onDownload = { viewModel.download(episode.id) },
                                        onRemoveDownload = { viewModel.removeDownload(episode.id) },
                                        onTogglePlayed = { viewModel.togglePlayed(episode.id) }
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
    ExtendedFloatingActionButton(onClick = onClick, modifier = modifier.testTag(TestTags.RESUME_PILL)) {
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
    podcastArtworkUrl: String?,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    isStarting: Boolean,
    livePositionMillis: Long?,
    liveDurationMillis: Long?,
    onPlay: () -> Unit,
    onEnqueue: () -> Unit,
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onTogglePlayed: () -> Unit
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

        // Falls back to the show's own art, since most feeds only set per-episode artwork
        // occasionally - same expression the Home feed uses, so a given episode looks the same
        // in both lists rather than showing art in one place and a bare row in the other.
        PodcastArtwork(
            artworkUrl = episode.artworkUrl ?: podcastArtworkUrl,
            modifier = Modifier.size(EpisodeArtworkSize)
        )

        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
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

            // Null date on purpose: this row already prints it in the header above, and passing
            // it again would repeat it. Everything else - "20m left" vs "51m" vs "Played", and
            // whether a bar is drawn at all - is the same rule the Home feed and the detail
            // screen use, so an episode reads identically wherever you meet it.
            val progress = episodeProgressUi(
                pubDateMillis = null,
                durationMillis = episode.durationMillis,
                positionMillis = episode.positionMillis,
                isPlayed = episode.isPlayed,
                // Home passes these and this list did not, so the row you were actually listening
                // to advanced in the ~5s steps of the persisted position rather than moving.
                livePositionMillis = livePositionMillis,
                liveDurationMillis = liveDurationMillis
            )
            val label = listOfNotNull(
                progress.label.takeIf { it.isNotEmpty() },
                downloadStatusLabel(download)
            ).joinToString(" · ")
            if (label.isNotEmpty()) {
                Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
            if (progress.showBar) {
                EpisodeProgressBar(
                    positionMillis = progress.positionMillis!!,
                    durationMillis = progress.durationMillis!!,
                    modifier = Modifier.padding(top = 6.dp, end = 8.dp)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row {
                // A spinner in the button's place, not beside it, so the row does not reflow -
                // the wait between tapping play and hearing anything is real (controller
                // connection, then buffering) and used to look like nothing had happened.
                IconButton(onClick = onPlay, enabled = !isStarting) {
                    if (isStarting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play ${episode.title}")
                    }
                }
                EpisodeActionsMenu(
                    episodeTitle = episode.title,
                    isPlayed = episode.isPlayed,
                    download = download,
                    onEnqueue = onEnqueue,
                    onDownload = onDownload,
                    onRemoveDownload = onRemoveDownload,
                    onTogglePlayed = onTogglePlayed
                )
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
