package com.solewis.podcaster.testing

import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity

fun podcastRow(
    title: String = "Test Show",
    feedUrl: String = "https://example.com/${title.lowercase().replace(' ', '-')}.xml",
    artworkUrl: String? = null,
    subscribedAt: Long = 1_000L
) = PodcastEntity(feedUrl = feedUrl, title = title, artworkUrl = artworkUrl, subscribedAt = subscribedAt)

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
