package com.solewis.podcaster.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable

/**
 * Just the back button, not a full `TopAppBar` - that reserves a fixed 64dp bar for a single
 * icon, which is the same dead-space problem [ScreenTitle] exists to avoid, just for detail
 * screens (Show, Show preview, Now Playing) that need a back affordance instead of a title.
 */
@Composable
fun BackButtonRow(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
    }
}
