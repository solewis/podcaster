package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.StreamCache

/** A [StreamCache] a test can set a size on and observe being cleared. */
class FakeStreamCache(var bytes: Long = 0L) : StreamCache {

    var cleared = false
        private set

    override suspend fun sizeBytes(): Long = bytes

    override suspend fun clear() {
        cleared = true
        bytes = 0L
    }
}
