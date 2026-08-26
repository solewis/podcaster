package com.solewis.podcaster.testing

import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity

/**
 * Row builders for tests that need a specific library shape rather than whatever a captured feed
 * happens to contain - ordering edge cases, a half-listened episode, a trailer with no chronoIndex.
 * Every parameter defaults to something valid so each test names only the fields it cares about.
 */
fun podcastRow(
    title: String = "Test Show",
    feedUrl: String = "https://example.com/${title.lowercase().replace(' ', '-')}.xml",
    artworkUrl: String? = "https://example.com/show.png",
    subscribedAt: Long = 1_000L,
    /**
     * Defaults to [subscribedAt] - matching [TestGraph.clock], so an inserted show counts as just
     * fetched and the automatic refreshes skip it. Without that, every test holding a subscription
     * would trip `refreshStale`/`refreshIfStale` into a live request against example.com. Pass an
     * older value (or advance the clock) in a test that wants the automatic refresh to fire.
     */
    lastRefreshedAt: Long? = subscribedAt
) = PodcastEntity(
    feedUrl = feedUrl,
    title = title,
    artworkUrl = artworkUrl,
    subscribedAt = subscribedAt,
    lastRefreshedAt = lastRefreshedAt
)

fun episodeRow(
    podcastId: Long,
    key: String,
    title: String = "Episode $key",
    /** Null marks a trailer or bonus episode - the distinction most ordering logic turns on. */
    chronoIndex: Int? = key.toIntOrNull(),
    feedPosition: Int = chronoIndex ?: 0,
    positionMillis: Long = 0,
    isPlayed: Boolean = false,
    lastPlayedAt: Long? = null,
    pubDateMillis: Long? = null,
    durationMillis: Long? = null,
    artworkUrl: String? = null,
    episodeType: String = if (chronoIndex == null) "trailer" else "full"
) = EpisodeEntity(
    id = "$podcastId:$key",
    podcastId = podcastId,
    stableKey = key,
    stableKeySource = "guid",
    title = title,
    enclosureUrl = "https://example.com/$key.mp3",
    durationMillis = durationMillis,
    artworkUrl = artworkUrl,
    episodeType = episodeType,
    feedPosition = feedPosition,
    chronoIndex = chronoIndex,
    displayNumber = chronoIndex,
    positionMillis = positionMillis,
    isPlayed = isPlayed,
    lastPlayedAt = lastPlayedAt,
    pubDateMillis = pubDateMillis,
    firstSeenAt = 1_000L
)
