package com.solewis.podcaster.data.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * The few preferences that belong to the app rather than to one particular show, and so have
 * nowhere to live in Room. Currently just playback speed.
 *
 * `SharedPreferences` rather than DataStore: this is one float, read when the player is built and
 * written only when a new speed is chosen from a menu. DataStore's advantages - a `Flow` and
 * off-main-thread writes - buy nothing at that shape, and would add a dependency plus an
 * asynchronous read on the path that decides what Now Playing shows on its first frame.
 *
 * Several of these can exist at once (the graph holds one, [com.solewis.podcaster.player.PlayerConnection]
 * builds its own by default) without any risk of them disagreeing: `getSharedPreferences` caches
 * one instance per file name per process, so they all read and write the same object.
 */
class PlaybackSettings(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Lazy because the first access loads the file synchronously - `SharedPreferences` blocks on
     * its own load latch - and constructing this must stay free, since it happens while assembling
     * [com.solewis.podcaster.AppContainer]. A single-entry file is sub-millisecond to load.
     */
    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    /**
     * Applies to every episode, not per show - a deliberate choice, though [com.solewis.podcaster.data.db.entity.PodcastEntity]
     * carries an unused `playbackSpeedOverride` column for the day that stops being true.
     *
     * Written by [com.solewis.podcaster.player.SpeedPersister] whenever the player's speed
     * changes, and read back when the player is built, so a chosen speed survives the process
     * being killed.
     */
    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, DEFAULT_SPEED)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    private companion object {
        const val NAME = "playback"
        const val KEY_SPEED = "speed"
        const val DEFAULT_SPEED = 1f
    }
}
