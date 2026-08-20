package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeIdentityTest {

    @Test
    fun `unique guid is used as identity`() {
        val key = EpisodeIdentity.resolve(
            guid = "abc-123",
            guidIsUniqueInFeed = true,
            enclosureUrl = "https://example.com/ep.mp3",
            title = "Title",
            pubDateEpochMillis = 1000L,
            enclosureBytes = 500L
        )
        assertThat(key.source).isEqualTo(EpisodeIdentity.Source.GUID)
        assertThat(key.value).isEqualTo("abc-123")
    }

    @Test
    fun `duplicate guid across feed falls back to enclosure URL`() {
        // Real-world bug this defends against: a feed reusing one static guid for every item.
        val key = EpisodeIdentity.resolve(
            guid = "static-guid-does-not-change",
            guidIsUniqueInFeed = false,
            enclosureUrl = "https://example.com/audio/ep7.mp3?token=abc7",
            title = "Episode 7",
            pubDateEpochMillis = 1000L,
            enclosureBytes = 500L
        )
        assertThat(key.source).isEqualTo(EpisodeIdentity.Source.ENCLOSURE)
    }

    @Test
    fun `missing guid falls back to enclosure URL`() {
        val key = EpisodeIdentity.resolve(
            guid = null,
            guidIsUniqueInFeed = false,
            enclosureUrl = "https://example.com/ep.mp3",
            title = "Title",
            pubDateEpochMillis = null,
            enclosureBytes = null
        )
        assertThat(key.source).isEqualTo(EpisodeIdentity.Source.ENCLOSURE)
    }

    @Test
    fun `blank guid falls back to enclosure URL`() {
        val key = EpisodeIdentity.resolve(
            guid = "   ",
            guidIsUniqueInFeed = true,
            enclosureUrl = "https://example.com/ep.mp3",
            title = "Title",
            pubDateEpochMillis = null,
            enclosureBytes = null
        )
        assertThat(key.source).isEqualTo(EpisodeIdentity.Source.ENCLOSURE)
    }

    @Test
    fun `missing guid and enclosure falls back to hash of title, date and bytes`() {
        val key = EpisodeIdentity.resolve(
            guid = null,
            guidIsUniqueInFeed = false,
            enclosureUrl = null,
            title = "Episode Title",
            pubDateEpochMillis = 1700000000L,
            enclosureBytes = 12345L
        )
        assertThat(key.source).isEqualTo(EpisodeIdentity.Source.HASH)
        assertThat(key.value).isNotEmpty()
    }

    @Test
    fun `hash fallback is deterministic for identical inputs`() {
        val a = EpisodeIdentity.resolve(null, false, null, "Same Title", 42L, 99L)
        val b = EpisodeIdentity.resolve(null, false, null, "Same Title", 42L, 99L)
        assertThat(a.value).isEqualTo(b.value)
    }

    @Test
    fun `hash fallback differs for different titles`() {
        val a = EpisodeIdentity.resolve(null, false, null, "Title A", 42L, 99L)
        val b = EpisodeIdentity.resolve(null, false, null, "Title B", 42L, 99L)
        assertThat(a.value).isNotEqualTo(b.value)
    }

    @Test
    fun `findDuplicateGuids identifies guids reused more than once`() {
        val duplicates = EpisodeIdentity.findDuplicateGuids(
            listOf("a", "b", "a", "c", "a", "d", "d")
        )
        assertThat(duplicates).containsExactly("a", "d")
    }

    @Test
    fun `findDuplicateGuids ignores null and blank entries`() {
        val duplicates = EpisodeIdentity.findDuplicateGuids(listOf(null, "", "  ", "x"))
        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `findDuplicateGuids returns empty set when all guids are unique - real feed shape`() {
        // Both captured real feeds had zero duplicate guids across thousands of items.
        val duplicates = EpisodeIdentity.findDuplicateGuids(listOf("g1", "g2", "g3", "g4"))
        assertThat(duplicates).isEmpty()
    }

    @Test
    fun `enclosure normalization strips query string so rotating tokens do not orphan progress`() {
        val a = EpisodeIdentity.normalizeEnclosureUrl(
            "https://cdn.example.com/audio/ep1.mp3?token=abc111&updated=1700000001"
        )
        val b = EpisodeIdentity.normalizeEnclosureUrl(
            "https://cdn.example.com/audio/ep1.mp3?token=xyz999&updated=1700099999"
        )
        assertThat(a).isEqualTo(b)
        assertThat(a).isEqualTo("cdn.example.com/audio/ep1.mp3")
    }

    @Test
    fun `enclosure normalization lowercases the host but preserves path case`() {
        val normalized = EpisodeIdentity.normalizeEnclosureUrl("https://CDN.Example.COM/Audio/Ep1.mp3")
        assertThat(normalized).isEqualTo("cdn.example.com/Audio/Ep1.mp3")
    }

    @Test
    fun `enclosure normalization strips known tracking wrapper prefix - real podtrac shape`() {
        // Real wrapper convention seen in feeds.simplecast.com/54nAGcIl enclosure URLs
        val normalized = EpisodeIdentity.normalizeEnclosureUrl(
            "https://dts.podtrac.com/redirect.mp3/cdn.example.com/ep1.mp3"
        )
        assertThat(normalized).isEqualTo("cdn.example.com/ep1.mp3")
    }

    @Test
    fun `enclosure normalization handles a malformed URL without throwing`() {
        val normalized = EpisodeIdentity.normalizeEnclosureUrl("not a url at all ??? ###")
        assertThat(normalized).isNotNull()
    }

    @Test
    fun `primaryKey is stable for the same key value`() {
        val key = EpisodeIdentity.Key("guid-123", EpisodeIdentity.Source.GUID)
        assertThat(EpisodeIdentity.primaryKey(1L, key)).isEqualTo(EpisodeIdentity.primaryKey(1L, key))
    }

    @Test
    fun `primaryKey differs across podcasts for the same key value`() {
        val key = EpisodeIdentity.Key("guid-123", EpisodeIdentity.Source.GUID)
        assertThat(EpisodeIdentity.primaryKey(1L, key)).isNotEqualTo(EpisodeIdentity.primaryKey(2L, key))
    }
}
