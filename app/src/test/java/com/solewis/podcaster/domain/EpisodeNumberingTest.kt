package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EpisodeNumberingTest {

    private fun input(
        feedPosition: Int,
        pubDate: Long?,
        itunesEpisode: Int? = null,
        type: String = "full"
    ) = EpisodeNumbering.Input(feedPosition, pubDate, itunesEpisode, type)

    @Test
    fun `assigns chronoIndex 1 to oldest by pub date when all items are dated`() {
        // Feed order is newest-first (feedPosition 0 = newest), as real feeds do it.
        val items = listOf(
            input(feedPosition = 0, pubDate = 3000L), // newest
            input(feedPosition = 1, pubDate = 2000L),
            input(feedPosition = 2, pubDate = 1000L)  // oldest
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(2).chronoIndex).isEqualTo(1) // oldest -> chronoIndex 1
        assertThat(results.getValue(1).chronoIndex).isEqualTo(2)
        assertThat(results.getValue(0).chronoIndex).isEqualTo(3) // newest -> highest chronoIndex
    }

    @Test
    fun `falls back to feed position when any item is missing a pub date - real Lex Fridman shape`() {
        // Real feed had 54 of 501 items missing itunes:duration; pub dates were present there,
        // but this proves the same "any missing -> whole feed falls back" rule for dates.
        val items = listOf(
            input(feedPosition = 0, pubDate = 3000L),
            input(feedPosition = 1, pubDate = null), // missing date anywhere disables date sort
            input(feedPosition = 2, pubDate = 1000L)
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        // Falls back to feedPosition descending: highest feedPosition = oldest = chronoIndex 1.
        assertThat(results.getValue(2).chronoIndex).isEqualTo(1)
        assertThat(results.getValue(1).chronoIndex).isEqualTo(2)
        assertThat(results.getValue(0).chronoIndex).isEqualTo(3)
    }

    @Test
    fun `displayNumber uses chronoIndex when itunes episode numbers are absent - both real feeds`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 2000L, itunesEpisode = null),
            input(feedPosition = 1, pubDate = 1000L, itunesEpisode = null)
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(1).displayNumber).isEqualTo(1)
        assertThat(results.getValue(0).displayNumber).isEqualTo(2)
    }

    @Test
    fun `displayNumber uses itunes episode numbers when present and distinct on every item`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L, itunesEpisode = 1),
            input(feedPosition = 1, pubDate = 2000L, itunesEpisode = 2),
            input(feedPosition = 2, pubDate = 3000L, itunesEpisode = 3)
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(0).displayNumber).isEqualTo(1)
        assertThat(results.getValue(1).displayNumber).isEqualTo(2)
        assertThat(results.getValue(2).displayNumber).isEqualTo(3)
    }

    @Test
    fun `never mixes numbering sources - falls back to chronoIndex for the whole feed when any item lacks itunes episode`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L, itunesEpisode = 1),
            input(feedPosition = 1, pubDate = 2000L, itunesEpisode = null), // missing on just this one
            input(feedPosition = 2, pubDate = 3000L, itunesEpisode = 3)
        )
        val results = EpisodeNumbering.assign(items)

        // All items fall back to chronoIndex-based numbering, none use the raw itunes value.
        assertThat(results.map { it.displayNumber }).containsExactly(1, 2, 3)
    }

    @Test
    fun `never mixes numbering sources - falls back when itunes episode numbers are duplicated`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L, itunesEpisode = 1),
            input(feedPosition = 1, pubDate = 2000L, itunesEpisode = 1), // duplicate value
            input(feedPosition = 2, pubDate = 3000L, itunesEpisode = 3)
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(0).displayNumber).isEqualTo(1) // chronoIndex, not the raw "1"/"1" clash
        assertThat(results.getValue(1).displayNumber).isEqualTo(2)
        assertThat(results.getValue(2).displayNumber).isEqualTo(3)
    }

    @Test
    fun `trailers and bonus episodes get null chronoIndex and null displayNumber`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L, type = "full"),
            input(feedPosition = 1, pubDate = 1500L, type = "trailer"),
            input(feedPosition = 2, pubDate = 2000L, type = "bonus")
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(0).chronoIndex).isEqualTo(1)
        assertThat(results.getValue(1).chronoIndex).isNull()
        assertThat(results.getValue(1).displayNumber).isNull()
        assertThat(results.getValue(2).chronoIndex).isNull()
        assertThat(results.getValue(2).displayNumber).isNull()
    }

    @Test
    fun `trailers and bonus episodes do not affect full-episode numbering`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L, type = "full"),
            input(feedPosition = 1, pubDate = 1500L, type = "trailer"),
            input(feedPosition = 2, pubDate = 2000L, type = "full")
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        assertThat(results.getValue(0).chronoIndex).isEqualTo(1)
        assertThat(results.getValue(2).chronoIndex).isEqualTo(2)
    }

    @Test
    fun `empty feed returns empty result`() {
        assertThat(EpisodeNumbering.assign(emptyList())).isEmpty()
    }

    @Test
    fun `feed with only non-full episodes assigns no display numbers`() {
        val items = listOf(input(feedPosition = 0, pubDate = 1000L, type = "trailer"))
        val results = EpisodeNumbering.assign(items)
        assertThat(results.single().displayNumber).isNull()
    }

    @Test
    fun `equal pub dates break ties by descending feed position`() {
        val items = listOf(
            input(feedPosition = 0, pubDate = 1000L),
            input(feedPosition = 1, pubDate = 1000L)
        )
        val results = EpisodeNumbering.assign(items).associateBy { it.feedPosition }

        // Higher feedPosition (later in a newest-first document) is treated as older.
        assertThat(results.getValue(1).chronoIndex).isEqualTo(1)
        assertThat(results.getValue(0).chronoIndex).isEqualTo(2)
    }
}
