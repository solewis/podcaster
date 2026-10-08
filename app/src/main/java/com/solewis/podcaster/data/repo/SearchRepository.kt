package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.remote.ItunesSearchApi

data class PodcastSearchResult(
    val itunesCollectionId: Long?,
    val title: String,
    val author: String?,
    val feedUrl: String,
    val artworkUrl: String?,
    val episodeCountHint: Int?
)

class SearchRepository(private val api: ItunesSearchApi = ItunesSearchApi()) {

    suspend fun search(term: String): List<PodcastSearchResult> {
        if (term.isBlank()) return emptyList()
        // api.searchPodcasts() already drops results with no feedUrl.
        return api.searchPodcasts(term)
            // iTunes can list one show under several catalogue entries that share a feed (Rotoviz
            // Radio comes back twice). Same feed, same subscription - so it is one result, and the
            // first is kept because the API orders by relevance.
            .distinctBy { it.feedUrl }
            .map { result ->
                PodcastSearchResult(
                    itunesCollectionId = result.collectionId,
                    title = result.collectionName?.takeIf(String::isNotBlank) ?: "(untitled show)",
                    author = result.artistName,
                    feedUrl = requireNotNull(result.feedUrl),
                    artworkUrl = result.artworkUrl600 ?: result.artworkUrl100,
                    episodeCountHint = result.trackCount
                )
            }
    }
}
