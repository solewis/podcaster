package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.EpisodeDao
import com.solewis.podcaster.data.db.PodcastDao
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.SortOrder
import com.solewis.podcaster.data.remote.FeedFetchException
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.domain.EpisodeIdentity
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.domain.HtmlToText

sealed class SubscribeResult {
    data class Success(val podcastId: Long) : SubscribeResult()
    data class AlreadySubscribed(val podcastId: Long) : SubscribeResult()
    data class Failure(val message: String) : SubscribeResult()
}

sealed class RefreshResult {
    data class Success(val episodesAdded: Int) : RefreshResult()
    data object NotModified : RefreshResult()
    data class Failure(val message: String) : RefreshResult()
}

/**
 * Owns the two operations that turn a feed URL into rows in Room: the initial subscribe (a full
 * fetch + parse + import) and a later refresh (conditional GET, then a metadata-only update that
 * never touches playback columns - see the warning on [EpisodeEntity]).
 */
class SubscriptionRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val feedFetcher: FeedFetcher = FeedFetcher(),
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun subscribe(
        feedUrl: String,
        itunesCollectionId: Long? = null,
        seedTitle: String? = null,
        seedArtworkUrl: String? = null
    ): SubscribeResult {
        podcastDao.findByFeedUrl(feedUrl)?.let { return SubscribeResult.AlreadySubscribed(it.id) }

        val fetchResult = try {
            feedFetcher.fetch(feedUrl, etag = null, lastModified = null)
        } catch (e: FeedFetchException) {
            return SubscribeResult.Failure(e.message ?: "Failed to fetch feed")
        }
        val feed = fetchResult.feed
            ?: return SubscribeResult.Failure("Feed returned no content") // fetchResult.notModified is impossible with no prior etag

        val timestamp = now()
        val podcast = PodcastEntity(
            feedUrl = feedUrl,
            itunesCollectionId = itunesCollectionId,
            title = feed.channel.title?.takeIf(String::isNotBlank) ?: seedTitle ?: "(untitled show)",
            author = feed.channel.author,
            description = HtmlToText.toPlainText(feed.channel.description),
            artworkUrl = feed.channel.imageUrl ?: seedArtworkUrl,
            websiteUrl = feed.channel.link,
            feedKind = feed.channel.itunesType,
            subscribedAt = timestamp,
            lastRefreshedAt = timestamp,
            httpEtag = fetchResult.etag,
            httpLastModified = fetchResult.lastModified,
            sortOrder = if (feed.channel.itunesType == "serial") SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
        )
        val podcastId = podcastDao.insert(podcast)

        val entities = FeedToEpisodesMapper.map(feed.items).map { it.toEntity(podcastId, timestamp) }
        episodeDao.insertNew(entities)

        return SubscribeResult.Success(podcastId)
    }

    suspend fun refresh(podcastId: Long): RefreshResult {
        val podcast = podcastDao.getById(podcastId)
            ?: return RefreshResult.Failure("Show no longer exists")

        val fetchResult = try {
            feedFetcher.fetch(podcast.feedUrl, podcast.httpEtag, podcast.httpLastModified)
        } catch (e: FeedFetchException) {
            podcastDao.recordRefreshFailure(podcastId, now(), e.message)
            return RefreshResult.Failure(e.message ?: "Refresh failed")
        }

        val timestamp = now()
        if (fetchResult.notModified) {
            podcastDao.recordRefreshSuccess(podcastId, fetchResult.etag, fetchResult.lastModified, timestamp)
            return RefreshResult.NotModified
        }

        val feed = fetchResult.feed
        if (feed == null) {
            podcastDao.recordRefreshFailure(podcastId, timestamp, "Feed returned no content")
            return RefreshResult.Failure("Feed returned no content")
        }

        val entities = FeedToEpisodesMapper.map(feed.items).map { it.toEntity(podcastId, timestamp) }
        val existingIds = episodeDao.getAllIdsForPodcast(podcastId).toSet()

        val newEntities = entities.filter { it.id !in existingIds }
        if (newEntities.isNotEmpty()) episodeDao.insertNew(newEntities)

        entities.forEach { entity ->
            episodeDao.updateMetadata(
                id = entity.id,
                title = entity.title,
                descriptionHtml = entity.descriptionHtml,
                pubDateMillis = entity.pubDateMillis,
                enclosureUrl = entity.enclosureUrl,
                enclosureBytes = entity.enclosureBytes,
                enclosureMimeType = entity.enclosureMimeType,
                artworkUrl = entity.artworkUrl,
                itunesEpisodeNumber = entity.itunesEpisodeNumber,
                itunesSeason = entity.itunesSeason,
                episodeType = entity.episodeType,
                webPageUrl = entity.webPageUrl,
                feedPosition = entity.feedPosition,
                chronoIndex = entity.chronoIndex,
                displayNumber = entity.displayNumber,
                durationMillis = entity.durationMillis
            )
        }

        val vanishedIds = existingIds - entities.map { it.id }.toSet()
        if (vanishedIds.isNotEmpty()) episodeDao.deleteIfNeverPlayed(podcastId, vanishedIds.toList())

        podcastDao.recordRefreshSuccess(podcastId, fetchResult.etag, fetchResult.lastModified, timestamp)
        return RefreshResult.Success(episodesAdded = newEntities.size)
    }

    private fun FeedToEpisodesMapper.MappedEpisode.toEntity(podcastId: Long, firstSeenAt: Long) = EpisodeEntity(
        id = EpisodeIdentity.primaryKey(podcastId, stableKey),
        podcastId = podcastId,
        stableKey = stableKey,
        stableKeySource = stableKeySource,
        title = title,
        descriptionHtml = descriptionHtml,
        pubDateMillis = pubDateMillis,
        enclosureUrl = enclosureUrl,
        enclosureBytes = enclosureBytes,
        enclosureMimeType = enclosureMimeType,
        durationMillis = durationMillis,
        durationIsExact = false,
        artworkUrl = artworkUrl,
        itunesEpisodeNumber = itunesEpisodeNumber,
        itunesSeason = itunesSeason,
        episodeType = episodeType,
        webPageUrl = webPageUrl,
        feedPosition = feedPosition,
        chronoIndex = chronoIndex,
        displayNumber = displayNumber,
        firstSeenAt = firstSeenAt
    )
}
