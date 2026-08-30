package com.solewis.podcaster.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Confirms throwing away a finished download.
 *
 * Deleting one used to be a single tap on an unlabelled icon, with no warning and no way to tell
 * from looking what the tap would do - so the first time you found out was when the episode
 * vanished from Downloads. Tens of megabytes, and possibly the only copy you can play without a
 * connection, deserve to be named before they go.
 *
 * Cancelling a download still in progress is deliberately *not* routed through here: it has taken
 * nothing but time so far, and interrupting it is what an obvious cancel button is for.
 */
@Composable
fun DeleteDownloadDialog(episodeTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete download?") },
        text = {
            Text(
                "\"$episodeTitle\" will be removed from this device. You can download it again, " +
                    "but not without a connection."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } }
    )
}
