package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.CachedEpisode
import com.solewis.podcaster.data.repo.StreamCache

/** A [StreamCache] a test can seed with entries and observe being cleared or trimmed. */
class FakeStreamCache : StreamCache {

    private val byId = mutableMapOf<String, CachedEpisode>()

    var cleared = false
        private set

    val removed = mutableListOf<String>()

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
