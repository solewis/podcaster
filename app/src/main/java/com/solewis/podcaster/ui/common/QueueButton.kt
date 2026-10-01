package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp

/**
 * Add to queue, or take back out - one control, shared by every list row and the episode screen so
 * they cannot disagree about what a queued episode looks like.
 *
 * It used to be a bare add icon that looked identical before and after a tap, reported as there
 * being no way to tell whether adding had worked. Queued, it shows a check and takes the accent
 * colour, so the state is readable at a glance rather than only from the queue screen. The app-wide
 * snackbar confirms each change too (see PodcasterRoot), which covers the moment of tapping; this
 * covers every moment after.
 */
@Composable
fun QueueButton(
    episodeTitle: String,
    isQueued: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = Dp.Unspecified
) {
    IconButton(onClick = onToggle, modifier = modifier.testTag(TestTags.enqueueButton(episodeTitle))) {
        Icon(
            if (isQueued) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
            // Says what a tap will do, the same as the play/pause button beside it.
            contentDescription = if (isQueued) "Remove $episodeTitle from queue" else "Add $episodeTitle to queue",
            tint = if (isQueued) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = if (iconSize == Dp.Unspecified) Modifier else Modifier.size(iconSize)
        )
    }
}
