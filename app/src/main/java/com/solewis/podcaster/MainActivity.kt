package com.solewis.podcaster

import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Bumped each time something asks for Now Playing - the notification, or the car's open-app.
     *
     * A counter rather than a flag because the same request can arrive twice and must act twice:
     * tap the notification, navigate away, tap it again. `singleTop` means the second tap arrives
     * at [onNewIntent] on the running Activity rather than building a new one.
     */
    private val openNowPlayingRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeNowPlayingRequest(intent)
        val container = (application as PodcasterApp).container
        setContent {
            // Read here rather than inside the theme, because the theme wraps everything below and
            // has no business knowing where the preference lives. Seeded with the current value so
            // the first frame is already correct - collecting alone would flash the default first.
            val theme by container.settings.observe()
                .collectAsState(initial = container.settings.snapshot())
            PodcasterTheme(darkTheme = theme.theme.isDark()) {
                PodcasterRoot(
                    container = container,
                    openNowPlayingRequests = openNowPlayingRequests
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keeps getIntent() honest for anything reading it later; without this the Activity would
        // keep reporting whatever launched it originally.
        setIntent(intent)
        consumeNowPlayingRequest(intent)
    }

    private fun consumeNowPlayingRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true) {
            openNowPlayingRequests.value++
            // Removed once acted on, so a rotation - which re-delivers the same intent - does not
            // drag you back to Now Playing after you have navigated away.
            intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "openNowPlaying"
    }
}

@androidx.compose.runtime.Composable
private fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
