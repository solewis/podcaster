package com.solewis.podcaster.ui.episodedetail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.db.model.EpisodeDetailItem
import com.solewis.podcaster.data.repo.EpisodeDownload
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.RemoveDone
import com.solewis.podcaster.ui.common.DownloadButton
import com.solewis.podcaster.ui.common.EpisodeActionsMenu
import com.solewis.podcaster.ui.common.DetailTopBar
import com.solewis.podcaster.ui.common.EpisodeProgressBar
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.episodeProgressUi
import com.solewis.podcaster.ui.common.htmlToAnnotatedString
import androidx.compose.ui.platform.testTag
import com.solewis.podcaster.ui.common.TestTags


@Composable
fun EpisodeDetailScreen(viewModel: EpisodeDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    // Where the heading and the bar currently are on screen, so the bar can take the title over at
    // exactly the moment the heading goes behind it. Compared as on-screen bounds rather than
    // against a scroll offset: the offset the heading sits at depends on the artwork, the show
    // name, the progress line and whether there is a progress bar at all, so any threshold would
    // have been a guess that quietly drifted as this screen changed.
    var titleBottom by remember { mutableFloatStateOf(Float.MAX_VALUE) }
    var barBottom by remember { mutableFloatStateOf(0f) }
    // derivedStateOf so only the crossing recomposes anything - these two floats change on every
    // frame of a scroll, and the bar only cares about the moment one passes the other.
    val showBarTitle by remember { derivedStateOf { titleBottom < barBottom } }

    Scaffold(
        modifier = Modifier.testTag(TestTags.EPISODE_DETAIL_SCREEN),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val episode = state.episode

            DetailTopBar(
                title = episode?.title.orEmpty(),
                onBack = onBack,
                showTitle = episode != null && showBarTitle,
                modifier = Modifier.onGloballyPositioned { barBottom = it.boundsInRoot().bottom }
            )

            when {
                episode != null -> EpisodeDetailContent(
                    episode = episode,
                    isPlayingThis = state.isPlayingThis,
                    isStarting = state.isStarting,
                    livePositionMillis = state.livePositionMillis,
                    liveDurationMillis = state.liveDurationMillis,
                    onTogglePlay = viewModel::togglePlay,
                    onEnqueue = viewModel::enqueue,
                    download = state.download,
                    onDownload = viewModel::download,
                    onRemoveDownload = viewModel::removeDownload,
                    onTogglePlayed = viewModel::togglePlayed,
                    onTitleBottomChanged = { titleBottom = it }
                )

                state.isLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun EpisodeDetailContent(
    episode: EpisodeDetailItem,
    isPlayingThis: Boolean,
    isStarting: Boolean,
    livePositionMillis: Long?,
    liveDurationMillis: Long?,
    onTogglePlay: () -> Unit,
    onEnqueue: () -> Unit,
    download: EpisodeDownload?,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onTogglePlayed: () -> Unit,
    onTitleBottomChanged: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PodcastArtwork(
                artworkUrl = episode.artworkUrl ?: episode.podcastArtworkUrl,
                modifier = Modifier.size(88.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                val numberLabel = episode.displayNumber?.let { "Ep $it" }
                    ?: episode.episodeType.takeIf { it != "full" }?.replaceFirstChar(Char::uppercase)
                numberLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                // Show name kept on the detail screen too - arriving here from the cross-show Home
                // feed, the episode title alone often isn't enough to say what you're looking at.
                Text(
                    episode.podcastTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            episode.title,
            style = MaterialTheme.typography.headlineSmall,
            // Reports itself on every scroll frame; the bar decides what to do about it.
            modifier = Modifier.onGloballyPositioned { onTitleBottomChanged(it.boundsInRoot().bottom) }
        )

        val progress = episodeProgressUi(
            pubDateMillis = episode.pubDateMillis,
            durationMillis = episode.durationMillis,
            positionMillis = episode.positionMillis,
            isPlayed = episode.isPlayed,
            livePositionMillis = livePositionMillis,
            liveDurationMillis = liveDurationMillis
        )
        if (progress.label.isNotEmpty()) {
            Text(
                progress.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (progress.showBar) {
            EpisodeProgressBar(
                positionMillis = progress.positionMillis!!,
                durationMillis = progress.durationMillis!!,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onTogglePlay, enabled = !isStarting, modifier = Modifier.weight(1f)) {
                // The button keeps its label while starting rather than swapping to a bare spinner,
                // so it does not change width and jump the row it sits in.
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                }
                Text(
                    if (isStarting) "Starting" else playButtonLabel(isPlayingThis, progress.showBar, episode.isPlayed),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // The same three controls in the same order as an episode row, and bare rather than
            // outlined. Play is already a labelled, filled button taking the width the other three
            // leave; giving those three containers of their own as well made a row of four things
            // all claiming to be buttons of roughly equal standing.
            IconButton(
                onClick = onEnqueue,
                modifier = Modifier.testTag(TestTags.enqueueButton(episode.title))
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = "Add ${episode.title} to queue"
                )
            }
            DownloadButton(
                episodeTitle = episode.title,
                download = download,
                onDownload = onDownload,
                onRemove = onRemoveDownload
            )
            // Everything that is neither "play it" nor "keep it" goes behind the overflow, the
            // same as on every episode row. Marking finished used to be a labelled text button
            // sitting on its own line under Play, which read like a second primary action for
            // something that is bookkeeping.
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

        Spacer(modifier = Modifier.height(24.dp))
        DescriptionSection(episode.descriptionHtml)
        Spacer(modifier = Modifier.height(32.dp))
    }
}

private fun playButtonLabel(isPlayingThis: Boolean, isStarted: Boolean, isPlayed: Boolean): String = when {
    isPlayingThis -> "Pause"
    isStarted -> "Resume"
    isPlayed -> "Play again"
    else -> "Play"
}

@Composable
private fun DescriptionSection(descriptionHtml: String?) {
    val linkColor = MaterialTheme.colorScheme.primary
    val description = remember(descriptionHtml, linkColor) {
        descriptionHtml?.let { htmlToAnnotatedString(it, linkColor) }
    }

    if (description == null) {
        Text("No description available.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    // Shown in full, with no collapse. Truncating at eight lines was worth a tap when it saved you
    // scrolling past the notes to reach something - but the description is the last thing on this
    // screen, so the only thing "Show more" ever revealed was the text directly above where the
    // button itself had been.
    Text(description, style = MaterialTheme.typography.bodyMedium)
}
