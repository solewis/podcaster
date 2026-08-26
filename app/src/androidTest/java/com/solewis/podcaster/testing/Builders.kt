package com.solewis.podcaster.testing

import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity

fun podcastRow(
    title: String = "Test Show",
    feedUrl: String = "https://example.com/${title.lowercase().replace(' ', '-')}.xml",
    artworkUrl: String? = null,
    subscribedAt: Long = 1_000L,
    /**
     * The wall clock, not [subscribedAt]: on-device tests run the real repositories with the real
     * clock, so an epoch-1970 timestamp would read as long overdue and send the app's automatic
     * refresh off to fetch example.com for real.
     */
    lastRefreshedAt: Long? = System.currentTimeMillis()
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
    chronoIndex: Int? = key.toIntOrNull(),
    pubDateMillis: Long? = 1_700_000_000_000,
    durationMillis: Long? = null,
    positionMillis: Long = 0,
    isPlayed: Boolean = false
) = EpisodeEntity(
    id = "$podcastId:$key",
    podcastId = podcastId,
    stableKey = key,
    stableKeySource = "guid",
    title = title,
    enclosureUrl = "https://example.com/$key.mp3",
    durationMillis = durationMillis,
    feedPosition = chronoIndex ?: 0,
    chronoIndex = chronoIndex,
    displayNumber = chronoIndex,
    positionMillis = positionMillis,
    isPlayed = isPlayed,
    pubDateMillis = pubDateMillis,
    firstSeenAt = 1_000L
)
