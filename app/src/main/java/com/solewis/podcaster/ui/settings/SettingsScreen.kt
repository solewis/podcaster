package com.solewis.podcaster.ui.settings

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.solewis.podcaster.data.settings.SkipAmount
import com.solewis.podcaster.data.settings.ThemeMode
import com.solewis.podcaster.ui.common.BackButtonRow
import com.solewis.podcaster.ui.common.ScreenTitle
import com.solewis.podcaster.ui.common.SkipIcon
import com.solewis.podcaster.ui.common.TestTags

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.settings.collectAsState()

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
                            modifier = Modifier.testTag(TestTags.themeChoice(mode))
                        )
                    }
                }
            }
            HorizontalDivider()
            ToggleRow(
                title = "Start playing when the car connects",
                // Says what it governs, without promising more than it can deliver: Android Auto
                // has its own resume behaviour that no app can decline.
                subtitle = "Begin the episode you were listening to as soon as Android Auto connects. It is loaded and ready either way.",
                checked = settings.autoPlayInCar,
                onCheckedChange = viewModel::setAutoPlayInCar,
                testTag = TestTags.AUTO_PLAY_IN_CAR_SWITCH
            )
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
        }
    }
}

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
