package com.solewis.podcaster.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun UnsubscribeConfirmDialog(podcastTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsubscribe from $podcastTitle?") },
        text = { Text("This removes all its episodes and your listening progress. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Unsubscribe") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
