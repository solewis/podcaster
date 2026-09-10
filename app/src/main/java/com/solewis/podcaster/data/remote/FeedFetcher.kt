package com.solewis.podcaster.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A feed that could not be fetched or read.
 *
 * An [IOException], deliberately. Everything that goes wrong on this path is one: OkHttp's
 * `execute()` throws `UnknownHostException`, `ConnectException`, `SocketTimeoutException` and
 * friends directly, and callers that caught only this type let all of those straight through. With
 * no connectivity that killed the process - see the tests in `SubscriptionRepositoryTest`. Sharing
 * a supertype with the failures it sits alongside means one `catch (e: IOException)` covers the
 * whole family, and a new transport failure cannot quietly escape a caller that was written before
 * it existed.
 */
class FeedFetchException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class FeedFetchResult(
    val notModified: Boolean,
    val feed: ParsedFeed?,
    val etag: String?,
    val lastModified: String?
)

/**
 * Fetches and parses a podcast RSS feed, using conditional GET so a refresh is nearly free when
 * nothing has changed. Verified live against two real feeds before writing this: both honor
 * `If-None-Match`/`If-Modified-Since` and return a bare `304` with zero response bytes - the
 * difference between that and re-downloading an 18.5 MB feed on every app open.
 */
class FeedFetcher(private val httpClient: OkHttpClient = HttpClient.instance) {

    suspend fun fetch(url: String, etag: String?, lastModified: String?): FeedFetchResult =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url)
            etag?.let { requestBuilder.header("If-None-Match", it) }
            lastModified?.let { requestBuilder.header("If-Modified-Since", it) }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == 304) {
                    return@withContext FeedFetchResult(
                        notModified = true,
                        feed = null,
                        etag = etag,
                        lastModified = lastModified
                    )
                }
                if (!response.isSuccessful) {
                    throw FeedFetchException("HTTP ${response.code} fetching $url")
                }
                val feed = try {
                    response.body.byteStream().use(RssParser::parse)
                } catch (e: RssParser.MalformedFeedException) {
                    throw FeedFetchException("Malformed feed at $url", e)
                }

                FeedFetchResult(
                    notModified = false,
                    feed = feed,
                    etag = response.header("ETag") ?: etag,
                    lastModified = response.header("Last-Modified") ?: lastModified
                )
            }
        }
}
