package com.solewis.podcaster.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.settings.PrefetchMode
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.data.settings.ThemeMode
import com.solewis.podcaster.ui.common.formatBytes
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.ScreenTitle
import com.solewis.podcaster.ui.common.SkipIcon
import com.solewis.podcaster.ui.common.TestTags

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val streamCacheBytes by viewModel.streamCacheBytes.collectAsState()
    val downloadBytes by viewModel.downloadBytes.collectAsState()
    val context = LocalContext.current

    Scaffold(
        modifier = Modifier.testTag(TestTags.SETTINGS_SCREEN),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
        ) {
            BackButtonRow(onBack)
            ScreenTitle("Settings")

            SettingSection("Skip back") {
                SkipAmountRow(
                    selected = settings.skipBack,
                    forward = false,
                    onSelect = viewModel::setSkipBack
                )
            }
            HorizontalDivider()
            SettingSection("Skip forward") {
                SkipAmountRow(
                    selected = settings.skipForward,
                    forward = true,
                    onSelect = viewModel::setSkipForward
                )
            }
            HorizontalDivider()
            SettingSection("Theme") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                            label = { Text(mode.label()) },
                            colors = accentChipColors(),
                            modifier = Modifier.testTag(TestTags.themeChoice(mode))
                        )
                    }
                }
            }
            HorizontalDivider()
            ToggleRow(
                title = "Play the next episode automatically",
                // Says what happens, not what the flag is called: the queue keeps its contents
                // either way, so the honest description is about what happens at the end.
                subtitle = "When an episode finishes, continue with your queue or the next unplayed episode.",
                checked = settings.autoAdvance,
                onCheckedChange = viewModel::setAutoAdvance,
                testTag = TestTags.AUTO_ADVANCE_SWITCH
            )
            HorizontalDivider()
            SettingSection("Downloading while you listen") {
                Text(
                    "Full episode pulls the rest of an episode in as soon as it starts, so a " +
                        "dropped connection later has nothing left to fetch. Conservative only " +
                        "downloads a little ahead at a time, using less data for episodes you " +
                        "don't finish.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrefetchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.prefetchMode == mode,
                            onClick = { viewModel.setPrefetchMode(mode) },
                            label = { Text(mode.label()) },
                            colors = accentChipColors(),
                            modifier = Modifier.testTag(TestTags.prefetchChoice(mode))
                        )
                    }
                }
            }
            if (settings.prefetchMode == PrefetchMode.FULL_EPISODE) {
                ToggleRow(
                    title = "Only on wifi",
                    subtitle = "Wait for wifi before pulling in the rest of an episode, rather than using cellular data.",
                    checked = settings.prefetchWifiOnly,
                    onCheckedChange = viewModel::setPrefetchWifiOnly,
                    testTag = TestTags.PREFETCH_WIFI_ONLY_SWITCH
                )
            }
            HorizontalDivider()
            StorageSection(
                title = "Streaming cache",
                description = "Episodes you stream, kept so replaying or resuming doesn't " +
                    "re-download them. Bounded on its own - clearing it just means the next " +
                    "listen refetches from the start.",
                sizeLabel = formatBytes(streamCacheBytes),
                actionLabel = "Clear cache",
                onAction = viewModel::clearStreamCache,
                testTag = TestTags.CLEAR_STREAM_CACHE
            )
            HorizontalDivider()
            StorageSection(
                title = "Downloads",
                description = "Episodes you chose to keep offline. Manage them one at a time " +
                    "from Downloads, or clear all of them here.",
                sizeLabel = formatBytes(downloadBytes),
                actionLabel = "Remove all downloads",
                onAction = viewModel::removeAllDownloads,
                testTag = TestTags.REMOVE_ALL_DOWNLOADS
            )
            HorizontalDivider()
            PlaybackLogSection(
                onShare = {
                    val text = viewModel.playbackLogText()
                    if (text.isBlank()) return@PlaybackLogSection
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Podcaster playback log")
                                putExtra(Intent.EXTRA_TEXT, text)
                            },
                            "Share playback log"
                        )
                    )
                },
                onClear = viewModel::clearPlaybackLog
            )
        }
    }
}


/**
 * Selected chips in the app's own accent rather than `FilterChip`'s default, which is the *warm
 * secondary* - so with a warm background the selected and unselected states differed mostly in
 * warmth, not in colour, and "which one is on" was genuinely hard to read at a glance.
 */
@Composable
private fun accentChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)

@Composable
private fun SettingSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/**
 * Each option shows the icon it will actually put on the transport controls, at the size it will
 * appear - so choosing is a matter of recognising the button rather than reading a number and
 * imagining it. The numeral is drawn into the glyph, which is what makes this possible; see
 * [SkipIcon].
 */
@Composable
private fun SkipAmountRow(selected: SkipAmount, forward: Boolean, onSelect: (SkipAmount) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SkipAmount.entries.forEach { amount ->
            FilterChip(
                selected = selected == amount,
                onClick = { onSelect(amount) },
                leadingIcon = {
                    SkipIcon(
                        seconds = amount.seconds,
                        forward = forward,
                        contentDescription = "",
                        modifier = Modifier.size(20.dp)
                    )
                },
                label = { Text("${amount.seconds}s") },
                colors = accentChipColors(),
                modifier = Modifier.testTag(TestTags.skipChoice(forward, amount))
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}

private fun PrefetchMode.label(): String = when (this) {
    PrefetchMode.FULL_EPISODE -> "Full episode"
    PrefetchMode.CONSERVATIVE -> "Conservative"
}

/** Shared shape for the two "how much is on disk, and how do I get rid of it" sections. */
@Composable
private fun StorageSection(
    title: String,
    description: String,
    sizeLabel: String,
    actionLabel: String,
    onAction: () -> Unit,
    testTag: String
) {
    SettingSection(title) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(sizeLabel, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onAction, modifier = Modifier.testTag(testTag)) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * A way to get the playback log off the phone.
 *
 * It exists for a bug that only happens occasionally, on a real device, away from a computer:
 * resume an episode, listen for about a minute, and playback jumps back and replays that minute.
 * Nothing reproduces it on demand, so the phone has to be able to say afterwards what it did - and
 * the person holding the phone has to be able to send that without plugging it in. Hence a share
 * sheet rather than a log this only `adb` can reach.
 *
 * Shared as text rather than as a file, which keeps a `FileProvider` and a granted URI out of the
 * app for the sake of a few kilobytes of plain lines.
 */
@Composable
private fun PlaybackLogSection(onShare: () -> Unit, onClear: () -> Unit) {
    SettingSection("Diagnostics") {
        Text(
            "Records what moves playback - seeks, item changes, the player's own state. " +
                "Share it after something goes wrong, with roughly the time it happened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare, modifier = Modifier.testTag(TestTags.SHARE_PLAYBACK_LOG)) {
                Text("Share playback log")
            }
            TextButton(onClick = onClear, modifier = Modifier.testTag(TestTags.CLEAR_PLAYBACK_LOG)) {
                Text("Clear")
            }
        }
    }
}
