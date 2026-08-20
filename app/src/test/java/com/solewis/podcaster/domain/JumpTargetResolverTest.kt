package com.solewis.podcaster.domain

import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.model.EpisodeListItem
import org.junit.Test

class JumpTargetResolverTest {

    private fun episode(
        id: String,
        chronoIndex: Int?,
        isPlayed: Boolean = false,
        lastPlayedAt: Long? = null,
        episodeType: String = "full",
        positionMillis: Long = 0
    ) = EpisodeListItem(
        id = id,
        podcastId = 1L,
        title = "Episode $id",
        pubDateMillis = null,
        durationMillis = null,
        displayNumber = chronoIndex,
        chronoIndex = chronoIndex,
        episodeType = episodeType,
        artworkUrl = null,
        positionMillis = positionMillis,
        isPlayed = isPlayed,
        lastPlayedAt = lastPlayedAt
    )

    @Test
    fun `no episode ever played returns no target`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, lastPlayedAt = null),
            episode("ep2", chronoIndex = 2, lastPlayedAt = null)
        )
        assertThat(JumpTargetResolver.resolve(episodes)).isNull()
    }

    @Test
    fun `empty show returns no target`() {
        assertThat(JumpTargetResolver.resolve(emptyList())).isNull()
    }

    @Test
    fun `unfinished last-listened episode resolves to RESUME on itself`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = false, lastPlayedAt = 1000L, positionMillis = 5000)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isEqualTo(JumpTargetResolver.Target("ep1", JumpTargetResolver.Intent.RESUME))
    }

    @Test
    fun `finished last-listened episode with a later unplayed episode resolves to NEXT`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = false, lastPlayedAt = null)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isEqualTo(JumpTargetResolver.Target("ep2", JumpTargetResolver.Intent.NEXT))
    }

    @Test
    fun `finished last-listened episode with nothing after it resolves to REVISIT on itself`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isEqualTo(JumpTargetResolver.Target("ep1", JumpTargetResolver.Intent.REVISIT))
    }

    @Test
    fun `NEXT picks the nearest unplayed episode by chronoIndex, not just any later one`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = false),
            episode("ep3", chronoIndex = 3, isPlayed = false)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target?.episodeId).isEqualTo("ep2")
    }

    @Test
    fun `NEXT skips over already-played episodes to find the first unplayed one`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = true),
            episode("ep3", chronoIndex = 3, isPlayed = false)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target?.episodeId).isEqualTo("ep3")
    }

    @Test
    fun `NEXT ignores trailers and bonus episodes even if unplayed`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("bonus1", chronoIndex = null, isPlayed = false, episodeType = "bonus"),
            episode("ep2", chronoIndex = 2, isPlayed = false)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target?.episodeId).isEqualTo("ep2")
    }

    @Test
    fun `out-of-order listening anchors on the most recently listened episode, not the furthest one`() {
        // User binged ep1-3 in order, then jumped ahead to ep10 out of curiosity.
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = true, lastPlayedAt = 2000L),
            episode("ep3", chronoIndex = 3, isPlayed = true, lastPlayedAt = 3000L),
            episode("ep10", chronoIndex = 10, isPlayed = false, lastPlayedAt = 9000L, positionMillis = 100)
        )
        val target = JumpTargetResolver.resolve(episodes)
        // Anchors on ep10 (most recent activity), not "next after the deepest played run" (ep4).
        assertThat(target).isEqualTo(JumpTargetResolver.Target("ep10", JumpTargetResolver.Intent.RESUME))
    }

    @Test
    fun `finishing the out-of-order episode then resolves NEXT relative to that episode, not ep4`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = true, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = true, lastPlayedAt = 2000L),
            episode("ep3", chronoIndex = 3, isPlayed = true, lastPlayedAt = 3000L),
            episode("ep10", chronoIndex = 10, isPlayed = true, lastPlayedAt = 9000L),
            episode("ep11", chronoIndex = 11, isPlayed = false)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isEqualTo(JumpTargetResolver.Target("ep11", JumpTargetResolver.Intent.NEXT))
    }

    @Test
    fun `a trailer as the last-listened episode with null chronoIndex safely falls back to REVISIT`() {
        val episodes = listOf(
            episode("trailer1", chronoIndex = null, isPlayed = true, lastPlayedAt = 1000L, episodeType = "trailer"),
            episode("ep1", chronoIndex = 1, isPlayed = false)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isEqualTo(JumpTargetResolver.Target("trailer1", JumpTargetResolver.Intent.REVISIT))
    }

    @Test
    fun `ties in lastPlayedAt still resolve to exactly one target`() {
        val episodes = listOf(
            episode("ep1", chronoIndex = 1, isPlayed = false, lastPlayedAt = 1000L),
            episode("ep2", chronoIndex = 2, isPlayed = false, lastPlayedAt = 1000L)
        )
        val target = JumpTargetResolver.resolve(episodes)
        assertThat(target).isNotNull()
    }
}
