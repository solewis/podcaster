package com.solewis.podcaster.data.repo

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One episode's worth of cached audio. [episodeId] is the cache key, which is also the episode's
 * Room primary key - see [com.solewis.podcaster.player.MediaItemMapper]. */
data class CachedEpisode(val episodeId: String, val sizeBytes: Long, val lastTouchedAtMillis: Long)

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

    /** Every episode currently holding space, so a person can see what a 512MB rolling cache
     * actually filled up with - and get rid of one thing without losing all of them. */
    suspend fun entries(): List<CachedEpisode>

    /** One episode's cached bytes, freed by hand rather than waiting for it to be evicted. */
    suspend fun remove(episodeId: String)

    /**
     * Drops anything not touched - played, or re-cached - in longer than [maxAgeMillis].
     *
     * The 512MB size limit alone lets a rolling cache hold onto an episode nobody has any
     * intention of finishing indefinitely, as long as nothing bigger ever needs the room. A time
     * limit is the difference between "bounded" and "actually rolls over".
     */
    suspend fun evictOlderThan(maxAgeMillis: Long)
}

@UnstableApi
class MediaStreamCache(private val cache: Cache) : StreamCache {

    override suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        // A snapshot first: removeResource mutates the same key set this would otherwise be
        // iterating live.
        cache.keys.toList().forEach { key -> runCatching { cache.removeResource(key) } }
    }

    override suspend fun entries(): List<CachedEpisode> = withContext(Dispatchers.IO) {
        cache.keys.map { key ->
            val spans = cache.getCachedSpans(key)
            CachedEpisode(
                episodeId = key,
                sizeBytes = spans.sumOf { it.length.coerceAtLeast(0) },
                // The most recent touch across every span this episode holds - a partial cache
                // (buffered ahead but not finished) is still "current" as long as any of it was
                // touched recently.
                lastTouchedAtMillis = spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L
            )
        }
    }

    override suspend fun remove(episodeId: String) {
        withContext(Dispatchers.IO) { runCatching { cache.removeResource(episodeId) } }
    }

    override suspend fun evictOlderThan(maxAgeMillis: Long) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        entries().forEach { entry ->
            if (entry.lastTouchedAtMillis < cutoff) runCatching { cache.removeResource(entry.episodeId) }
        }
    }
}
