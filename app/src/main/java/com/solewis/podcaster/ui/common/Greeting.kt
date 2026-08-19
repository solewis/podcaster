package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.R
import com.solewis.podcaster.ui.theme.PodcasterTheme

/**
 * Placeholder root screen for Increment 1. Exists to prove Compose compiles, Material 3 theming
 * resolves (including dynamic color), and edge-to-edge insets are handled correctly - nothing
 * more. Real navigation (Library/Search/Queue/Settings) replaces this in Phase 2+.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcasterRootPlaceholder() {
    val appName = stringResource(id = R.string.app_name)
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(appName) })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                text = "Scaffolding is up. Next: git, tests, CI.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PodcasterRootPlaceholderPreview() {
    PodcasterTheme {
        PodcasterRootPlaceholder()
    }
}
