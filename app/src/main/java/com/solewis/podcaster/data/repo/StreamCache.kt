package com.solewis.podcaster.data.repo

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the settings screen is allowed to know and do about the streaming cache - the bounded,
 * least-recently-used store [com.solewis.podcaster.player.MediaStorage.streamCache] keeps for
 * episodes played without an explicit download. Separate from [Downloads], which is the *other*
 * store: user-chosen, unbounded until removed by hand, and never touched by this.
 *
 * An interface for the same reason [Downloads] is one: the real implementation wraps a Media3
 * `Cache`, which cannot be built on the JVM, and a settings screen test should not need one to
 * assert what a "Clear cache" button does.
 */
interface StreamCache {

    /** Total bytes the streaming cache currently occupies on disk. */
    suspend fun sizeBytes(): Long

    /** Empties it. Nothing downloaded is affected - downloads live in a separate store entirely. */
    suspend fun clear()
}

@UnstableApi
class MediaStreamCache(private val cache: Cache) : StreamCache {

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // A snapshot first: removeResource mutates the same key set this would otherwise be
        // iterating live.
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }
}
