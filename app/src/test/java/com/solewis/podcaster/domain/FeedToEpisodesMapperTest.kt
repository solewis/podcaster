package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.remote.RssParser
import org.junit.Test

class FeedToEpisodesMapperTest {

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("feeds/$name")) { "Missing fixture: feeds/$name" }

    @Test
    fun `real NYT Daily feed maps every item and assigns stable guid identity`() {
        val feed = RssParser.parse(fixture("nyt_daily_slice.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items)

        assertThat(mapped).hasSize(150)
        assertThat(mapped.all { it.stableKeySource == "guid" }).isTrue()
        assertThat(mapped.map { it.stableKey }.toSet()).hasSize(150) // all unique
        assertThat(mapped.all { it.durationMillis != null }).isTrue() // real feed: 0 missing
    }

    @Test
    fun `real Lex Fridman feed maps despite 54 missing durations`() {
        val feed = RssParser.parse(fixture("lex_fridman.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items)

        assertThat(mapped).hasSize(501)
        assertThat(mapped.count { it.durationMillis == null }).isEqualTo(54)
        // Every episode still gets identity + a chronoIndex despite missing durations.
        assertThat(mapped.all { it.chronoIndex != null }).isTrue()
    }

    @Test
    fun `duplicate guid feed falls back to enclosure-based identity for every item`() {
        val feed = RssParser.parse(fixture("duplicate_guids.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items)

        assertThat(mapped).hasSize(5)
        assertThat(mapped.all { it.stableKeySource == "enclosure" }).isTrue()
        assertThat(mapped.map { it.stableKey }.toSet()).hasSize(5) // enclosure URLs differ per item
    }

    @Test
    fun `serial feed with distinct itunes episode numbers uses them as displayNumber`() {
        val feed = RssParser.parse(fixture("serial_with_episode_numbers.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items).sortedBy { it.chronoIndex }

        assertThat(mapped.map { it.displayNumber }).isEqualTo((1..10).toList())
        assertThat(mapped.map { it.chronoIndex }).isEqualTo((1..10).toList())
    }

    @Test
    fun `inconsistent episode numbering feed falls back to chronoIndex for displayNumber`() {
        val feed = RssParser.parse(fixture("inconsistent_episode_numbers.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items).sortedBy { it.chronoIndex }

        // Oldest (Mar 1) -> chronoIndex 1, ... newest (Mar 15) -> chronoIndex 3.
        assertThat(mapped.map { it.displayNumber }).isEqualTo(listOf(1, 2, 3))
    }

    @Test
    fun `rotating tracking token identity is identical across two refreshes of the same episode`() {
        val v1 = FeedToEpisodesMapper.map(RssParser.parse(fixture("rotating_token_v1.xml")).items).single()
        val v2 = FeedToEpisodesMapper.map(RssParser.parse(fixture("rotating_token_v2.xml")).items).single()

        assertThat(v1.stableKey).isEqualTo(v2.stableKey)
        assertThat(v1.stableKeySource).isEqualTo("enclosure")
    }

    @Test
    fun `synthetic 4000-item stress feed maps completely without error`() {
        val feed = RssParser.parse(fixture("synthetic_stress_4000_items.xml"))
        val mapped = FeedToEpisodesMapper.map(feed.items)

        assertThat(mapped).hasSize(4000)
        assertThat(mapped.map { it.chronoIndex }.toSet()).hasSize(4000) // all distinct, 1..4000
    }

    @Test
    fun `untitled episode gets a placeholder title rather than a blank one`() {
        val item = com.solewis.podcaster.data.remote.ParsedItem(
            feedPosition = 0,
            title = null,
            guid = "g1",
            enclosureUrl = "https://example.com/ep.mp3"
        )
        val mapped = FeedToEpisodesMapper.map(listOf(item)).single()
        assertThat(mapped.title).isEqualTo("(untitled episode)")
    }

    @Test
    fun `items without an enclosure url are dropped entirely`() {
        val playable = com.solewis.podcaster.data.remote.ParsedItem(
            feedPosition = 0, guid = "g1", enclosureUrl = "https://example.com/ep.mp3"
        )
        val unplayable = com.solewis.podcaster.data.remote.ParsedItem(
            feedPosition = 1, guid = "g2", enclosureUrl = null
        )
        val mapped = FeedToEpisodesMapper.map(listOf(playable, unplayable))
        assertThat(mapped).hasSize(1)
        assertThat(mapped.single().stableKey).isEqualTo("g1")
    }
}
