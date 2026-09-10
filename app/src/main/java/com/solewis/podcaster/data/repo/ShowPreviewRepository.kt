package com.solewis.podcaster.data.repo

import java.io.IOException
import com.solewis.podcaster.data.remote.FeedFetcher
import com.solewis.podcaster.domain.FeedToEpisodesMapper
import com.solewis.podcaster.domain.HtmlToText

data class ShowPreview(
    val title: String,
    val author: String?,
    val description: String?,
    val artworkUrl: String?,
    /** Newest-published first, unless the feed is a serial - matches [SubscriptionRepository]'s
     * default `sortOrder` choice on actual subscribe, so a preview doesn't reorder on you the
     * moment you subscribe. No toggle here: that's a commitment decision for a show you've
     * decided to follow, not one you're still browsing. */
    val episodes: List<FeedToEpisodesMapper.MappedEpisode>
)

/**
 * Fetches and parses a feed live, with no Room persistence, so a show's episodes can be browsed
 * and played from Search before deciding to subscribe. Always a full fetch - there is no stored
 * etag/lastModified to condition on for a show that isn't subscribed yet, same as the very first
 * fetch [SubscriptionRepository.subscribe] does.
 */
class ShowPreviewRepository(private val feedFetcher: FeedFetcher = FeedFetcher()) {

    suspend fun load(feedUrl: String, seedTitle: String?): ShowPreview? {
        val result = try {
            feedFetcher.fetch(feedUrl, etag = null, lastModified = null)
        } catch (e: IOException) {
            // See SubscriptionRepository: OkHttp's transport failures are not FeedFetchException,
            // and catching only that let them escape and kill the process.
            return null
        }
        val feed = result.feed ?: return null

        val episodes = FeedToEpisodesMapper.map(feed.items)
        val sorted = if (feed.channel.itunesType == "serial") {
            episodes.sortedBy { it.chronoIndex ?: Int.MAX_VALUE }
        } else {
            episodes.sortedByDescending { it.chronoIndex ?: -1 }
        }

        return ShowPreview(
            title = feed.channel.title?.takeIf(String::isNotBlank) ?: seedTitle ?: "(untitled show)",
            author = feed.channel.author,
            description = HtmlToText.toPlainText(feed.channel.description),
            artworkUrl = feed.channel.imageUrl,
            episodes = sorted
        )
    }
}
