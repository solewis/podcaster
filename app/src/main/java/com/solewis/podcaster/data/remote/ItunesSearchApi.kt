package com.solewis.podcaster.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed class ItunesSearchException(message: String) : Exception(message)
class ItunesRateLimitedException : ItunesSearchException("Rate limited by the iTunes Search API (HTTP 429)")
class ItunesHttpException(val code: Int) : ItunesSearchException("iTunes Search API returned HTTP $code")

/**
 * Podcast discovery via the iTunes Search API. Verified live before writing this: no API key
 * needed, `limit` caps at 100 despite docs claiming 200, and throttling responds with HTTP 429
 * rather than 403.
 */
class ItunesSearchApi(
    private val httpClient: OkHttpClient = HttpClient.instance,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun searchPodcasts(term: String, limit: Int = 25): List<ItunesPodcastResult> =
        withContext(Dispatchers.IO) {
            val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
                .addQueryParameter("term", term)
                .addQueryParameter("media", "podcast")
                .addQueryParameter("entity", "podcast")
                .addQueryParameter("country", "US")
                .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
                .build()

            val request = Request.Builder().url(url).build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 429) throw ItunesRateLimitedException()
                if (!response.isSuccessful) throw ItunesHttpException(response.code)

                val parsed = json.decodeFromString<ItunesSearchResponse>(response.body.string())
                // A result with no feed URL can't be subscribed to - filter it out here so every
                // caller doesn't have to repeat this check.
                parsed.results.filter { !it.feedUrl.isNullOrBlank() }
            }
        }
}
