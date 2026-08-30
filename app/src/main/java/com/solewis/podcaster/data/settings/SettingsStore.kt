package com.solewis.podcaster.data.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * How far a skip jumps. Three fixed values rather than a free number, so each can be drawn with its
 * own numeral - both in the app ([com.solewis.podcaster.ui.common.SkipIcon] takes the seconds) and
 * in the car, where Media3 happens to ship `ICON_SKIP_BACK_5`/`_15`/`_30` and nothing in between.
 * A slider promising 17 seconds would have to lie in both places.
 */
enum class SkipAmount(val seconds: Int) {
    FIVE(5),
    FIFTEEN(15),
    THIRTY(30);

    val millis: Long get() = seconds * 1_000L
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Everything the settings screen owns, as one snapshot. */
data class AppSettings(
    val skipBack: SkipAmount = SkipAmount.FIFTEEN,
    val skipForward: SkipAmount = SkipAmount.FIFTEEN,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val autoAdvance: Boolean = true,
    val autoPlayInCar: Boolean = false
)

/**
 * App preferences - the handful of things that belong to the app rather than to one show, and so
 * have nowhere to live in Room.
 *
 * `SharedPreferences` rather than DataStore: this is four values, read when the player is built and
 * written only when someone picks something from a menu. DataStore's advantages - a `Flow` and
 * off-main-thread writes - buy little at that shape, and it would add a dependency plus an
 * asynchronous read on the path that decides what Now Playing draws on its first frame. The `Flow`
 * that is genuinely needed is [observe], which `SharedPreferences` supports directly.
 *
 * Several of these can exist at once (the graph holds one, [com.solewis.podcaster.player.PlayerConnection]
 * builds its own by default) without any risk of disagreeing: `getSharedPreferences` caches one
 * instance per file name per process, so they all read and write the same object. That is also what
 * makes [observe] work across the Activity/service boundary - they are the same process.
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Lazy because the first access loads the file synchronously - `SharedPreferences` blocks on its
     * own load latch - and constructing this must stay free, since it happens while assembling
     * [com.solewis.podcaster.AppContainer]. A four-entry file is sub-millisecond to load.
     */
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /**
     * Playback speed, kept out of [AppSettings] deliberately. It is written by
     * [com.solewis.podcaster.player.SpeedPersister] on a player callback rather than by a settings
     * screen, and folding it in would make every [observe] collector churn mid-playback - including
     * the playback service, which re-pushes its notification buttons on each emission.
     */
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var skipBack: SkipAmount
        get() = readSkip(KEY_SKIP_BACK)
        set(value) = prefs.edit().putInt(KEY_SKIP_BACK, value.seconds).apply()

    var skipForward: SkipAmount
        get() = readSkip(KEY_SKIP_FORWARD)
        set(value) = prefs.edit().putInt(KEY_SKIP_FORWARD, value.seconds).apply()

    var theme: ThemeMode
        get() = prefs.getString(KEY_THEME, null)?.let { name ->
            // Tolerates an unknown name rather than throwing: a value written by a newer build, or
            // a hand-edited backup, must not crash every launch.
            ThemeMode.entries.firstOrNull { it.name == name }
        } ?: ThemeMode.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var autoAdvance: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADVANCE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ADVANCE, value).apply()

    /**
     * Whether connecting to the car should start playing on its own.
     *
     * Off by default: sound starting by itself the moment an engine turns over is assertive enough
     * that it should be asked for. Note this governs whether *this app* starts playback - Android
     * Auto has its own resume-on-connect behaviour that an app cannot decline, so with that enabled
     * playback may still begin regardless. What the app guarantees either way is that the episode
     * is loaded and showing, which is the part that was actually missing.
     */
    var autoPlayInCar: Boolean
        get() = prefs.getBoolean(KEY_AUTO_PLAY_IN_CAR, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_PLAY_IN_CAR, value).apply()

    fun snapshot() = AppSettings(
        skipBack = skipBack,
        skipForward = skipForward,
        theme = theme,
        autoAdvance = autoAdvance,
        autoPlayInCar = autoPlayInCar
    )

    /**
     * Emits the current settings and then again on every change, so a new skip amount reaches the
     * player, the notification buttons and the on-screen icons without anything being restarted.
     * That matters most for the skip amounts: `ExoPlayer`'s own seek increments are fixed when it is
     * built, so [com.solewis.podcaster.player.TimedSkipPlayer] owns them instead and reads them per
     * seek.
     */
    fun observe(): Flow<AppSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // Speed changes on a timer's worth of player callbacks and is not part of the snapshot;
            // waking every collector for it would be pure noise.
            if (key != KEY_SPEED) trySend(snapshot())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun readSkip(key: String): SkipAmount {
        val seconds = prefs.getInt(key, SkipAmount.FIFTEEN.seconds)
        return SkipAmount.entries.firstOrNull { it.seconds == seconds } ?: SkipAmount.FIFTEEN
    }

    private companion object {
        const val NAME = "playback"
        const val KEY_SPEED = "speed"
        const val KEY_SKIP_BACK = "skipBackSeconds"
        const val KEY_SKIP_FORWARD = "skipForwardSeconds"
        const val KEY_THEME = "theme"
        const val KEY_AUTO_ADVANCE = "autoAdvance"
        const val KEY_AUTO_PLAY_IN_CAR = "autoPlayInCar"
    }
}
