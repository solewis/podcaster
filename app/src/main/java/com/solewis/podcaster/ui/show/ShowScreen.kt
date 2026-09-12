package com.solewis.podcaster.ui.show

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.data.repo.EpisodeDownload
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.ui.common.DetailTopBar
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.solewis.podcaster.ui.common.EpisodeActionRow
import com.solewis.podcaster.ui.common.EpisodeDescriptionPreview
import com.solewis.podcaster.ui.common.EpisodeMetaAndProgressRow
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
    var optionsOpen by remember { mutableStateOf(false) }

    // Same mechanism as the episode screen: compare where the show's name currently is against
    // where the bar is, rather than guessing a scroll offset that the artwork and author line
    // would invalidate. derivedStateOf so only the crossing recomposes.
    var tabsHeight by remember { mutableIntStateOf(0) }
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var barBottom by remember { mutableFloatStateOf(0f) }
    val showBarTitle by remember { derivedStateOf { titleBottom < barBottom } }

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

    val podcast = state.podcast
    // Generous on purpose: the row landing a little low is fine, the row landing half-hidden is not.
    val jumpClearance = with(LocalDensity.current) { 24.dp.roundToPx() }

    Scaffold(
        modifier = Modifier.testTag(TestTags.SHOW_SCREEN),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            DetailTopBar(
                title = podcast?.title.orEmpty(),
                onBack = onBack,
                showTitle = podcast != null && showBarTitle,
                modifier = Modifier.onGloballyPositioned { barBottom = it.boundsInRoot().bottom }
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (podcast != null) {
                // One list for the whole page, rather than a fixed header above a list. The show's
                // name, artwork and subscribe row used to be pinned, which on a phone spent a third
                // of the screen on information you read once - now they scroll away and the
                // episodes get that room back. The tab row is the only thing that stays.
                LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
                    item(key = HEADER_KEY) {
                        ShowHeader(
                            podcast = podcast,
                            onUnsubscribe = { pendingUnsubscribe = true },
                            menuOpen = showMenuOpen,
                            onMenuOpenChange = { showMenuOpen = it },
                            onMarkAllPlayed = viewModel::markAllPlayed,
                            onRefresh = viewModel::refresh,
                            onTitleBottomChanged = { titleBottom = it }
                        )
                    }

                    // The index parameter is the current overload's; this header does not need it.
                    stickyHeader(key = TABS_KEY) { _ ->
                        // Opaque: the episodes it pins above scroll underneath it.
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            // Measured because the jump pill has to scroll its target clear of this
                            // row - it now overlays the top of the list, and anything scrolled
                            // exactly to the top lands underneath it.
                            modifier = Modifier.onGloballyPositioned { tabsHeight = it.size.height }
                        ) {
                            SecondaryTabRow(selectedTabIndex = selectedTab) {
                                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Episodes") })
                                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("About") })
                            }
                        }
                    }

                    if (selectedTab == 0) {
                        item(key = SORT_KEY) {
                            EpisodeListControls(
                                sortOrder = podcast.sortOrder,
                                filter = state.filter,
                                isRefreshing = isRefreshing,
                                onOpenOptions = { optionsOpen = true }
                            )
                        }
                        items(state.episodes, key = { it.id }) { episode ->
                            EpisodeRow(
                                episode = episode,
                                podcastArtworkUrl = podcast.artworkUrl,
                                isHighlighted = episode.id == highlightedEpisodeId,
                                onClick = { onOpenEpisode(episode.id) },
                                isStarting = episode.id == pendingEpisodeId,
                                isNowPlaying = episode.id == nowPlayingId,
                                livePositionMillis = livePosition.takeIf { episode.id == nowPlayingId },
                                liveDurationMillis = liveDuration.takeIf { episode.id == nowPlayingId },
                                onPlay = {
                                    if (episode.id == nowPlayingId) viewModel.togglePlayPause()
                                    else viewModel.play(episode.id)
                                },
                                onEnqueue = { viewModel.enqueue(episode.id) },
                                download = downloadStates[episode.id],
                                onDownload = { viewModel.download(episode.id) },
                                onRemoveDownload = { viewModel.removeDownload(episode.id) },
                                onTogglePlayed = { viewModel.togglePlayed(episode.id) }
                            )
                            HorizontalDivider()
                        }
                    } else {
                        item(key = ABOUT_KEY) {
                            AboutTab(podcast = podcast, episodeCount = state.episodes.size)
                        }
                    }
                }

                if (jump != null && !isJumpTargetVisible && selectedTab == 0) {
                    JumpToLastListenedPill(
                        jump = jump,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        onClick = {
                            scope.launch {
                                // The pill's index counts episodes; the list now also holds the
                                // header, the tab row and the sort row ahead of them.
                                val target = jump.itemIndex + EPISODES_LEADING_ITEMS
                                // Negative, so the row lands this far *below* the top of the list
                                // rather than at it. Scrolling flush to the top now puts the row
                                // under the pinned tab row, which is what was clipping it - and a
                                // fixed nudge afterwards could not know how tall that row is.
                                val clearance = -(tabsHeight + jumpClearance)
                                val distance = abs(target - listState.firstVisibleItemIndex)
                                if (distance > 40) {
                                    listState.scrollToItem(target, clearance)
                                } else {
                                    listState.animateScrollToItem(target, clearance)
                                }

                                highlightedEpisodeId = jump.episodeId
                                delay(1200)
                                highlightedEpisodeId = null
                            }
                        }
                    )
                }
            } else if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    if (optionsOpen) {
        state.podcast?.let { podcast ->
            EpisodeListOptionsSheet(
                sortOrder = podcast.sortOrder,
                filter = state.filter,
                onSortOrderChange = viewModel::setSortOrder,
                onFilterChange = viewModel::setFilter,
                onDismiss = { optionsOpen = false }
            )
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

/**
 * The show's identity and the two things you can do to the show itself.
 *
 * No tinted surface behind it any more. The band of `surfaceContainerHigh` was there to mark this
 * off as a header, which mattered when it was pinned and the list scrolled under it - now that it
 * scrolls away with everything else the colour was just a slab that slid past, and the divider
 * underneath says the same thing more quietly.
 */
@Composable
private fun ShowHeader(
    podcast: PodcastEntity,
    onUnsubscribe: () -> Unit,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onMarkAllPlayed: () -> Unit,
    onRefresh: () -> Unit,
    onTitleBottomChanged: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    podcast.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Reports itself as it scrolls; the bar decides when to take the name over.
                    modifier = Modifier.onGloballyPositioned { onTitleBottomChanged(it.boundsInRoot().bottom) }
                )
                podcast.author?.let { author ->
                    Text(
                        author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            PodcastArtwork(artworkUrl = podcast.artworkUrl, modifier = Modifier.size(96.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubscribeButton(
                isSubscribed = true,
                isBusy = false,
                onClick = onUnsubscribe
            )
            // Beside the subscribe button rather than pushed to the far edge. Alone across the row
            // it read as belonging to the page at large; next to the only other control here, the
            // two are obviously the same kind of thing - what you can do to this show.
            Spacer(modifier = Modifier.width(4.dp))
            // A menu rather than another button: per-show actions are only going to accumulate (a
            // speed override, intro trimming), and the episode rows below have already run out of
            // room for trailing controls.
            Box {
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    modifier = Modifier.testTag(TestTags.SHOW_MENU)
                ) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More actions for this show")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                    // Refresh lives here rather than beside the list. Opening a show already checks
                    // the feed, and a periodic job checks them all, so a permanent button was an
                    // invitation to do by hand something the app has already done - but it is worth
                    // keeping for the moment you know a new episode is out and want it now.
                    DropdownMenuItem(
                        text = { Text("Check for new episodes") },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                        onClick = {
                            onMenuOpenChange(false)
                            onRefresh()
                        },
                        modifier = Modifier.testTag(TestTags.REFRESH_SHOW)
                    )
                    DropdownMenuItem(
                        text = { Text("Mark all as played") },
                        leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                        onClick = {
                            onMenuOpenChange(false)
                            onMarkAllPlayed()
                        },
                        modifier = Modifier.testTag(TestTags.MARK_ALL_PLAYED)
                    )
                    DropdownMenuItem(
                        text = { Text("Unsubscribe") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            onMenuOpenChange(false)
                            onUnsubscribe()
                        },
                        modifier = Modifier.testTag(TestTags.UNSUBSCRIBE_MENU_ITEM)
                    )
                }
            }
        }
        HorizontalDivider()
    }
}

/**
 * How the episode list is sorted and filtered, as one control that opens [EpisodeListOptionsSheet].
 *
 * The label names the sort order only, even when a filter is on. That is what this used to say and
 * it is what people read it for - but it does mean a filtered list could otherwise look like a
 * complete one, so the icon takes the accent colour whenever something is being hidden. A list
 * quietly missing episodes is a worse outcome than a slightly busier button.
 */
@Composable
private fun EpisodeListControls(
    sortOrder: SortOrder,
    filter: EpisodeFilter,
    isRefreshing: Boolean,
    onOpenOptions: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onOpenOptions,
            // A TextButton's content defaults to the accent colour, so the tint below used to be
            // the accent either way and the icon was permanently lit. Neutral here is what gives
            // the accent something to mean.
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.testTag(TestTags.EPISODE_OPTIONS)
        ) {
            Icon(
                Icons.Default.Tune,
                // Colour is the whole signal that episodes are being hidden, and colour is exactly
                // what a screen reader does not get - so the filter is named here when one is on.
                contentDescription = if (filter == EpisodeFilter.ALL) null
                else "Filtered to ${filter.label.lowercase()}",
                modifier = Modifier.size(18.dp),
                tint = if (filter == EpisodeFilter.ALL) LocalContentColor.current
                else MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (sortOrder == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first")
        }
        Spacer(modifier = Modifier.weight(1f))
        // The only trace refresh leaves on this screen now that its button has moved into the menu:
        // without it a tap on the menu item would look like nothing happened at all.
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp).padding(horizontal = 12.dp))
        }
    }
}

/**
 * Sort order and filter together in one sheet, because they are one question - what do you want to
 * see and in what order - and because a filter needs somewhere its options are all visible at once.
 * The old control cycled the sort order on every tap, which could only ever offer two states and
 * had nowhere to put a third thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeListOptionsSheet(
    sortOrder: SortOrder,
    filter: EpisodeFilter,
    onSortOrderChange: (SortOrder) -> Unit,
    onFilterChange: (EpisodeFilter) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(TestTags.EPISODE_OPTIONS_SHEET)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
            OptionsSectionLabel("Sort")
            SortOrder.entries.forEach { option ->
                OptionRow(
                    label = if (option == SortOrder.NEWEST_FIRST) "Newest first" else "Oldest first",
                    selected = option == sortOrder,
                    testTag = TestTags.sortOption(option),
                    onClick = { onSortOrderChange(option) }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            OptionsSectionLabel("Show")
            EpisodeFilter.entries.forEach { option ->
                OptionRow(
                    label = option.label,
                    selected = option == filter,
                    testTag = TestTags.filterOption(option),
                    onClick = { onFilterChange(option) }
                )
            }
        }
    }
}

@Composable
private fun OptionsSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

/** The whole row is the target, not just the button - a 24dp circle is a poor thing to aim at. */
@Composable
private fun OptionRow(label: String, selected: Boolean, testTag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val HEADER_KEY = "showHeader"
private const val TABS_KEY = "showTabs"
private const val SORT_KEY = "episodeSort"
private const val ABOUT_KEY = "about"

/** Header, tab row and sort row all sit ahead of the episodes in the same list. */
private const val EPISODES_LEADING_ITEMS = 3

@Composable
private fun AboutTab(podcast: PodcastEntity, episodeCount: Int) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
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
    isNowPlaying: Boolean,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(
                if (isHighlighted) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Centred against the artwork rather than top-aligned. The column beside it holds two short
        // lines to the artwork's 48dp, so aligning them to the top left the pair sitting high in the
        // row with the slack collecting underneath, which read as a misalignment.
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                // "Episode 1", not "Ep 1", and on a line of its own: this sits exactly where the
                // Home feed puts the show's name, in the same weight and accent colour, so a row
                // has the same shape in both lists. The date used to share this line and now goes
                // where Home already puts it, in the metadata line under the description.
                val numberLabel = episode.displayNumber?.let { "Episode $it" }
                    ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
                numberLabel?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        // Flush with the artwork rather than indented past it, matching the Home feed. Indented,
        // these lines started at a different place here than there for no reason a reader could
        // see, and the row lost the left edge that the title, artwork and controls all share.
        EpisodeDescriptionPreview(episode.descriptionPreview)

        // The date now comes through here rather than from the row's header, which is where the
        // Home feed has always carried it. Everything else - "20m left" vs "51m" vs "Finished",
        // and whether a bar is drawn at all - is the same rule the Home feed and the detail
        // screen use, so an episode reads identically wherever you meet it.
        val progress = episodeProgressUi(
            pubDateMillis = episode.pubDateMillis,
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
        EpisodeMetaAndProgressRow(
            progress = progress.copy(label = label),
            isPlayed = episode.isPlayed,
            // This list keeps the default onSurface rather than the muted variant the Home feed
            // uses, so the tick's own line stays as legible as the titles above it.
            color = MaterialTheme.colorScheme.onSurface
        )

        // This row had no pause state at all before this - it always drew a play arrow and always
        // started the episode from the top of `play()`, so the episode you were listening to
        // looked unplayed and the button could not stop it. The Home feed's rows have always done
        // this; these had been missed.
        EpisodeActionRow(
            episodeTitle = episode.title,
            isPlaying = isNowPlaying,
            isLoading = isStarting,
            onPlayOrToggle = onPlay,
            onEnqueue = onEnqueue,
            download = download,
            onDownload = onDownload,
            onRemoveDownload = onRemoveDownload,
            isPlayed = episode.isPlayed,
            onTogglePlayed = onTogglePlayed
        )
    }
}
