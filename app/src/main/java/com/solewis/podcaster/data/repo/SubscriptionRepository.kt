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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    /**
     * Refreshes every subscription, capped at [maxConcurrent] in flight at once - shared by the
     * Library screen's pull-to-refresh and the periodic background worker, so both follow the
     * same "don't hammer every feed host at once" rule.
     */
    suspend fun refreshAll(maxConcurrent: Int = 3): List<RefreshResult> =
        refreshEach(podcastDao.getAllIds(), maxConcurrent)

    /**
     * Refreshes only the subscriptions that have gone stale - what runs automatically when the app
     * is brought to the foreground, so new episodes are simply there rather than waiting behind a
     * manual refresh.
     *
     * Gated on [STALE_AFTER_MILLIS] rather than unconditional because foregrounding is a frequent
     * event (every task switch, every rotation) and this must not turn into a feed request each
     * time. Explicit refreshes - pull-to-refresh, the button on a show, the periodic worker -
     * still go through [refreshAll] and always ask.
     *
     * Cheap even when it does run: [FeedFetcher][com.solewis.podcaster.data.remote.FeedFetcher]
     * sends the stored `ETag`/`Last-Modified`, so an unchanged feed answers with a bare 304.
     */
    suspend fun refreshStale(maxConcurrent: Int = 3): List<RefreshResult> =
        refreshEach(podcastDao.getStaleIds(now() - STALE_AFTER_MILLIS), maxConcurrent)

    /**
     * One show, if it has gone stale - what runs on opening a show, where the interesting question
     * is always "is there a new episode". Null means it was fresh enough to skip, so a caller
     * showing a spinner can leave it down.
     */
    suspend fun refreshIfStale(podcastId: Long): RefreshResult? {
        val lastRefreshedAt = podcastDao.getById(podcastId)?.lastRefreshedAt
        if (lastRefreshedAt != null && now() - lastRefreshedAt < STALE_AFTER_MILLIS) return null
        return refresh(podcastId)
    }

    private suspend fun refreshEach(ids: List<Long>, maxConcurrent: Int): List<RefreshResult> =
        coroutineScope {
            val semaphore = Semaphore(maxConcurrent)
            ids.map { id -> async { semaphore.withPermit { refresh(id) } } }.awaitAll()
        }

    companion object {
        /**
         * How long a subscription stays "fresh enough" for the automatic refreshes to skip it.
         *
         * Fifteen minutes: long enough that flipping between apps, rotating the screen, or opening
         * two shows in a row costs no requests at all, short enough that any real return to the app
         * - after lunch, the next morning - checks. Podcast feeds do not meaningfully change faster
         * than this.
         */
        const val STALE_AFTER_MILLIS = 15 * 60 * 1000L
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
