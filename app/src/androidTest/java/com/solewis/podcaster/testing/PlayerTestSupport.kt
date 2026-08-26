package com.solewis.podcaster.testing

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared by the on-device player tests, which all face the same three problems: `ExoPlayer` insists
 * on its own application thread, its work is asynchronous even for silence, and a `SilenceMediaSource`
 * needs coaxing into carrying an episode id.
 */

/**
 * Runs [block] on the main thread and hands back its result.
 *
 * Both the player and the session wrapper throw if touched from anywhere else, and the
 * instrumentation thread a test body runs on is not it.
 */
fun <T> onMain(block: () -> T): T {
    var result: T? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * Polls until [condition] holds. ExoPlayer's work is asynchronous even for silence, so every
 * assertion downstream of it has to wait rather than read immediately.
 */
fun awaitPlayer(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for: $what")
}

/**
 * A seekable source of known duration that reports [mediaId] as its media id.
 *
 * `updateMediaItem`, not `Factory.setTag`: the tag only lands on `localConfiguration`, leaving
 * `SilenceMediaSource`'s own fixed media id in place - which silently makes every id-keyed lookup
 * downstream target something that does not exist.
 */
fun silenceSource(mediaId: String, durationMillis: Long): SilenceMediaSource =
    SilenceMediaSource(durationMillis * 1_000).apply {
        updateMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
    }
