package com.solewis.podcaster.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.PodcastArtwork
import com.solewis.podcaster.ui.common.SkipIcon
import com.solewis.podcaster.ui.common.formatTimer
import kotlin.math.abs

private const val SKIP_SECONDS = 15
private const val SEEK_SETTLE_TOLERANCE_MILLIS = 1_500L

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(viewModel: NowPlayingViewModel, onBack: () -> Unit) {
    val playback by viewModel.playback.collectAsState()
    val progress by viewModel.progress.collectAsState()

    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMillis by remember { mutableFloatStateOf(0f) }
    // Set on release, cleared once playback actually reports the seeked-to position. Without
    // this, the slider snaps back to the pre-seek position for the ~500ms until the next
    // progress tick catches up, then jumps again - looks like the drag didn't take.
    var pendingSeekMillis by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(progress.positionMillis, pendingSeekMillis) {
        val pending = pendingSeekMillis
        if (pending != null && abs(progress.positionMillis - pending) < SEEK_SETTLE_TOLERANCE_MILLIS) {
            pendingSeekMillis = null
        }
    }

    val durationMillis = progress.durationMillis?.coerceAtLeast(1L) ?: 1L
    val sliderPositionMillis = when {
        isDragging -> dragPositionMillis
        pendingSeekMillis != null -> pendingSeekMillis!!.toFloat()
        else -> progress.positionMillis.toFloat()
    }

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                BackButtonRow(onBack)
            }

            PodcastArtwork(
                artworkUrl = playback.artworkUrl,
                modifier = Modifier.size(280.dp),
                shape = MaterialTheme.shapes.extraLarge
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                playback.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            playback.podcastTitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Slider(
                value = sliderPositionMillis,
                valueRange = 0f..durationMillis.toFloat(),
                onValueChange = {
                    isDragging = true
                    dragPositionMillis = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    pendingSeekMillis = dragPositionMillis.toLong()
                    viewModel.seekTo(dragPositionMillis.toLong())
                },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatTimer(sliderPositionMillis.toLong()) ?: "0:00", style = MaterialTheme.typography.labelSmall)
                    if (pendingSeekMillis != null) {
                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                    }
                }
                Text(formatTimer(progress.durationMillis) ?: "--:--", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = viewModel::skipBack) {
                    SkipIcon(
                        seconds = SKIP_SECONDS,
                        forward = false,
                        contentDescription = "Back $SKIP_SECONDS seconds",
                        modifier = Modifier.size(36.dp)
                    )
                }
                FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = viewModel::skipForward) {
                    SkipIcon(
                        seconds = SKIP_SECONDS,
                        forward = true,
                        contentDescription = "Forward $SKIP_SECONDS seconds",
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SpeedControl(currentSpeed = playback.speed, onSpeedChange = viewModel::setSpeed)
        }
    }
}

@Composable
private fun SpeedControl(currentSpeed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text("Speed: ${formatSpeed(currentSpeed)}x")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEEDS.forEach { speed ->
                DropdownMenuItem(
                    text = { Text("${formatSpeed(speed)}x") },
                    leadingIcon = if (speed == currentSpeed) {
                        { Icon(Icons.Default.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        onSpeedChange(speed)
                        expanded = false
                    }
                )
            }
        }
    }
}

/** "1.0" reads worse than "1" for a whole-number speed; fractional speeds print as-is. */
private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
