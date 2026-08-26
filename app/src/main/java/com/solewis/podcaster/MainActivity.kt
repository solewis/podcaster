package com.solewis.podcaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.solewis.podcaster.data.settings.ThemeMode
import com.solewis.podcaster.ui.PodcasterRoot
import com.solewis.podcaster.ui.theme.PodcasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as PodcasterApp).container
        setContent {
            // Read here rather than inside the theme, because the theme wraps everything below and
            // has no business knowing where the preference lives. Seeded with the current value so
            // the first frame is already correct - collecting alone would flash the default first.
            val theme by container.settings.observe()
                .collectAsState(initial = container.settings.snapshot())
            PodcasterTheme(darkTheme = theme.theme.isDark()) {
                PodcasterRoot(container = container)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
