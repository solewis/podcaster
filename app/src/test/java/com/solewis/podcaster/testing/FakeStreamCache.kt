package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.CachedEpisode
import com.solewis.podcaster.data.repo.StreamCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A [StreamCache] a test can seed with entries and observe being cleared or trimmed. */
class FakeStreamCache : StreamCache {

    // Copy-on-write: written from the code under test, read from polling assertions, on different
    // threads. See FakeDownloads for the ConcurrentModificationException this prevents.
    private val byId: MutableMap<String, CachedEpisode> = ConcurrentHashMap()

    var cleared = false
        private set

    val removed: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun sizeBytes(): Long = byId.values.sumOf { it.sizeBytes }

    override suspend fun clear() {
        cleared = true
        byId.clear()
    }

    override suspend fun entries(): List<CachedEpisode> = byId.values.toList()

    override suspend fun remove(episodeId: String) {
        removed += episodeId
        byId -= episodeId
    }

    override suspend fun evictOlderThan(maxAgeMillis: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        byId.values.filter { it.lastTouchedAtMillis < cutoff }.forEach { remove(it.episodeId) }
    }

    /** Seeds an entry as if it had already been cached. */
    fun seed(episodeId: String, sizeBytes: Long, lastTouchedAtMillis: Long = System.currentTimeMillis()) {
        byId[episodeId] = CachedEpisode(episodeId, sizeBytes, lastTouchedAtMillis)
    }
}
