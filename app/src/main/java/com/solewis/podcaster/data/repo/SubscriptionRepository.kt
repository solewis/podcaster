package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.EpisodeDao
import com.solewis.podcaster.data.db.PodcastDao
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.SortOrder
import androidx.annotation.VisibleForTesting
import com.solewis.podcaster.data.remote.FeedFetchResult
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.data.remote.ParsedFeed
import com.solewis.podcaster.domain.EpisodeIdentity
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.domain.HtmlToText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

sealed class SubscribeResult {
    data class Success(val podcastId: Long) : SubscribeResult()
    data class AlreadySubscribed(val podcastId: Long) : SubscribeResult()
}

sealed class RefreshResult {
    data class Success(val episodesAdded: Int) : RefreshResult()
    data object NotModified : RefreshResult()
    data class Failure(val message: String) : RefreshResult()
}

/**
 * Owns the two operations that turn a feed URL into rows in Room: subscribing, which adds the show
 * at once and loads its episodes in the background, and refreshing - a conditional GET, then a
 * metadata-only update that never touches playback columns (see the warning on [EpisodeEntity]).
 */
class SubscriptionRepository(
    private val podcastDao: PodcastDao,
    private val episodeDao: EpisodeDao,
    private val feedFetcher: FeedFetcher = FeedFetcher(),
    /**
     * Where feed loads run, so that one outlives the screen that started it: subscribing returns
     * as soon as the show is added, and leaving Search mid-load must not abandon its episodes.
     * Injectable so a test can stop that work before closing its database.
     */
    private val loadScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = System::currentTimeMillis
) {
    /** Guarded by itself, together with [_loading], so the two never disagree. */
    private val inFlight = HashMap<Long, Deferred<RefreshResult>>()
    private val _loading = MutableStateFlow<Set<Long>>(emptySet())

    /** The shows whose feed is being fetched right now, so a show page can say it is loading. */
    val loading: StateFlow<Set<Long>> = _loading.asStateFlow()

    /**
     * Adds the show and returns straight away, with its episodes still loading in the background.
     *
     * The load used to come first, so Subscribe waited on the whole feed - for Rotoviz Radio that
     * is 25MB and 3,610 episodes, well over ten seconds on a phone on cellular. [feed] is the one a
     * show preview already fetched, if there is one, so subscribing from there downloads nothing.
     *
     * A load that fails leaves the show where it is, with the failure recorded on it. Never having
     * been refreshed, it counts as stale, so the next automatic refresh - opening the app, or
     * opening the show - is the retry.
     */
    suspend fun subscribe(
        feedUrl: String,
        itunesCollectionId: Long? = null,
        seedTitle: String? = null,
        seedArtworkUrl: String? = null,
        feed: FeedFetchResult? = null
    ): SubscribeResult = withContext(Dispatchers.IO) {
        podcastDao.findByFeedUrl(feedUrl)?.let { return@withContext SubscribeResult.AlreadySubscribed(it.id) }

        val podcastId = podcastDao.insert(
            PodcastEntity(
                feedUrl = feedUrl,
                itunesCollectionId = itunesCollectionId,
                // Placeholders until the feed arrives - see applyFeedDetails.
                title = seedTitle?.takeIf(String::isNotBlank) ?: "(untitled show)",
                artworkUrl = seedArtworkUrl,
                subscribedAt = now()
            )
        )
        load(podcastId, feed)
        SubscribeResult.Success(podcastId)
    }

    /**
     * Fetches the show's feed and applies it. Joins a load of the same show that is already under
     * way rather than starting a second - opening a show you have just subscribed to would
     * otherwise download its whole feed again alongside the first.
     */
    suspend fun refresh(podcastId: Long): RefreshResult = load(podcastId, feed = null).await()

    /** Waits for a load of [podcastId] already under way, if there is one - for tests. */
    @VisibleForTesting
    suspend fun awaitLoad(podcastId: Long): RefreshResult? = synchronized(inFlight) { inFlight[podcastId] }?.await()

    private fun load(podcastId: Long, feed: FeedFetchResult?): Deferred<RefreshResult> = synchronized(inFlight) {
        inFlight[podcastId]?.let { return it }

        val load = loadScope.async(start = CoroutineStart.LAZY) {
            withContext(Dispatchers.IO) { loadReportingFailure(podcastId, feed) }
        }
        inFlight[podcastId] = load
        _loading.value = inFlight.keys.toSet()
        load.invokeOnCompletion {
            synchronized(inFlight) {
                inFlight.remove(podcastId)
                _loading.value = inFlight.keys.toSet()
            }
        }
        load.start()
        load
    }

    /**
     * Reports rather than throws anything that is not a network failure - those are handled
     * inside. That is a bug, or the show was unsubscribed mid-load and its episodes have nothing
     * left to belong to. A load started by subscribing has nobody waiting on it to throw to, and
     * the show page's refresh runs in a Compose scope, where an escaping exception kills the app.
     */
    private suspend fun loadReportingFailure(podcastId: Long, feed: FeedFetchResult?): RefreshResult =
        try {
            loadOnCallerThread(podcastId, feed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Refresh failed"
            if (podcastDao.getById(podcastId) != null) podcastDao.recordRefreshFailure(podcastId, now(), message)
            RefreshResult.Failure(message)
        }

    /**
     * The load itself. Everything reaches it through [load], which is what puts it on
     * [Dispatchers.IO].
     *
     * That dispatcher is load-bearing rather than tidiness. The automatic refresh is launched from
     * a Compose scope (see PodcasterRoot), whose dispatcher is the main thread, and nothing below
     * here switched away from it - so parsing a feed's episodes into entities, stripping HTML from
     * every description to build its preview, and writing every row all happened on the main
     * thread. Reported as a one-to-two second delay before a fresh app would scroll at all, then
     * settling once the refreshes finished a minute or two later. `FeedFetcher` was already on IO,
     * which is exactly why this was easy to miss: the network call - the obvious slow part - was
     * the one piece already off the main thread.
     */
    private suspend fun loadOnCallerThread(podcastId: Long, feed: FeedFetchResult?): RefreshResult {
        val podcast = podcastDao.getById(podcastId)
            ?: return RefreshResult.Failure("Show no longer exists")

        val fetchResult = feed ?: try {
            feedFetcher.fetch(podcast.feedUrl, podcast.httpEtag, podcast.httpLastModified)
        } catch (e: IOException) {
            // UnknownHostException and its siblings come straight out of OkHttp's execute(). The
            // automatic refresh runs from a Compose scope on every foreground, so one escaping
            // here took the app down on launch with no connection - three times in twelve
            // seconds, because each relaunch tried again.
            val message = e.offlineMessage("Refresh failed")
            podcastDao.recordRefreshFailure(podcastId, now(), message)
            return RefreshResult.Failure(message)
        }

        val timestamp = now()
        if (fetchResult.notModified) {
            podcastDao.recordRefreshSuccess(podcastId, fetchResult.etag, fetchResult.lastModified, timestamp)
            return RefreshResult.NotModified
        }

        val parsed = fetchResult.feed
        if (parsed == null) {
            podcastDao.recordRefreshFailure(podcastId, timestamp, "Feed returned no content")
            return RefreshResult.Failure("Feed returned no content")
        }

        // Before the episodes, so they arrive already in the order the show page will keep.
        if (podcast.lastRefreshedAt == null) applyFeedDetails(podcast, parsed)

        val entities = FeedToEpisodesMapper.map(parsed.items).map { it.toEntity(podcastId, timestamp) }
        val existingIds = episodeDao.getAllIdsForPodcast(podcastId).toSet()

        val newEntities = entities.filter { it.id !in existingIds }
        if (newEntities.isNotEmpty()) episodeDao.insertNew(newEntities)

        // One transaction, not one call per episode - see EpisodeDao.updateMetadataForFeed.
        episodeDao.updateMetadataForFeed(entities)

        val vanishedIds = existingIds - entities.map { it.id }.toSet()
        if (vanishedIds.isNotEmpty()) episodeDao.deleteIfNeverPlayed(podcastId, vanishedIds.toList())

        podcastDao.recordRefreshSuccess(podcastId, fetchResult.etag, fetchResult.lastModified, timestamp)
        return RefreshResult.Success(episodesAdded = newEntities.size)
    }

    /**
     * Replaces the placeholders [subscribe] stored with what the feed says about itself, the first
     * time it loads. Only then: the sort order is the user's to change from here on.
     */
    private suspend fun applyFeedDetails(podcast: PodcastEntity, feed: ParsedFeed) {
        val channel = feed.channel
        podcastDao.applyFeedDetails(
            podcastId = podcast.id,
            title = channel.title?.takeIf(String::isNotBlank) ?: podcast.title,
            author = channel.author,
            description = HtmlToText.toPlainText(channel.description),
            artworkUrl = channel.imageUrl ?: podcast.artworkUrl,
            websiteUrl = channel.link,
            feedKind = channel.itunesType,
            sortOrder = if (channel.itunesType == "serial") SortOrder.OLDEST_FIRST else SortOrder.NEWEST_FIRST
        )
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

    /**
     * Refreshes many feeds, and reports on each one separately.
     *
     * The guard is not belt-and-braces. `coroutineScope` cancels its siblings and rethrows when any
     * child fails, so before this a single unreachable feed both aborted the refresh of every other
     * feed *and* threw into whatever scope called it - which for the automatic refresh is a Compose
     * scope, where an escaping exception is a dead process. A batch whose entire purpose is to
     * return one result per feed has no business doing either.
     *
     * Anything not an [IOException] is a bug rather than a bad connection, so it is reported as a
     * failure and left in the log rather than silently swallowed.
     */
    private suspend fun refreshEach(ids: List<Long>, maxConcurrent: Int): List<RefreshResult> =
        coroutineScope {
            val semaphore = Semaphore(maxConcurrent)
            ids.map { id ->
                async {
                    semaphore.withPermit {
                        try {
                            refresh(id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Not logged via android.util.Log: `isReturnDefaultValues` is off and
                            // eighteen test classes run without Robolectric, so a Log call here
                            // would throw "not mocked" the first time one of them reached it. The
                            // message travels in the result, which is where the UI reads it.
                            RefreshResult.Failure(e.message ?: "Refresh failed")
                        }
                    }
                }
            }.awaitAll()
        }

    private fun IOException.offlineMessage(fallback: String): String = when (this) {
        // Named rather than passed through, because the raw text is "Unable to resolve host
        // \"anchor.fm\": No address associated with hostname", which tells the user nothing they
        // can act on. This reaches the UI.
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "No connection"
        else -> message ?: fallback
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

        /** Long enough to read as a real excerpt rather than a fragment cut off mid-clause on a
         * list row, short enough that two lines of `bodySmall` never overflows it first anyway. */
        const val DESCRIPTION_PREVIEW_LENGTH = 200
    }

    private fun FeedToEpisodesMapper.MappedEpisode.toEntity(podcastId: Long, firstSeenAt: Long) = EpisodeEntity(
        id = EpisodeIdentity.primaryKey(podcastId, stableKey),
        podcastId = podcastId,
        stableKey = stableKey,
        stableKeySource = stableKeySource,
        title = title,
        descriptionHtml = descriptionHtml,
        descriptionPreview = HtmlToText.toPlainText(descriptionHtml)?.take(DESCRIPTION_PREVIEW_LENGTH),
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
