package com.solewis.podcaster.testing

import com.solewis.podcaster.data.remote.HttpClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * A local stand-in for a podcast host, so the ingest path can be tested over real HTTP without
 * reaching the internet. Real sockets and the real [HttpClient] singleton, which means these tests
 * cover OkHttp's gzip handling, the User-Agent interceptor, and conditional-GET header round-trips
 * rather than stubbing them out.
 *
 * Nothing in production had to change to allow this: [com.solewis.podcaster.data.remote.FeedFetcher]
 * takes the feed URL per call, so pointing it here is just a matter of passing [feedUrl].
 */
class FeedHost : Closeable {

    private val server = MockWebServer()

    /** Pass this as the podcast's feed URL. Queued responses are served in FIFO order. */
    fun feedUrl(path: String = "/feed.xml"): String = server.url(path).toString()

    fun enqueueFeed(fixture: String, etag: String? = null, lastModified: String? = null) {
        val response = MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/rss+xml")
            .setBody(Fixtures.feedText(fixture))
        etag?.let { response.setHeader("ETag", it) }
        lastModified?.let { response.setHeader("Last-Modified", it) }
        server.enqueue(response)
    }

    /**
     * What a real host returns for an unchanged feed: a bare 304 with no body at all.
     *
     * [delayMillis] holds the response open, which is how a test can be certain a request is still
     * in flight - needed to exercise anything guarding against a concurrent second request.
     */
    fun enqueueNotModified(delayMillis: Long = 0) {
        val response = MockResponse().setResponseCode(304)
        if (delayMillis > 0) response.setHeadersDelay(delayMillis, TimeUnit.MILLISECONDS)
        server.enqueue(response)
    }

    fun enqueueStatus(code: Int) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(""))
    }

    fun enqueueBody(body: String, contentType: String = "application/json") {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", contentType).setBody(body)
        )
    }

    val requestCount: Int get() = server.requestCount

    /** Blocks briefly for the next request the client actually made - use to assert on headers. */
    fun takeRequest(): RecordedRequest = server.takeRequest()

    /**
     * A client that sends every request here no matter what host it names, preserving path and
     * query. [com.solewis.podcaster.data.remote.ItunesSearchApi] builds an absolute
     * `itunes.apple.com` URL internally, so redirecting at the client is the only way to intercept
     * it - the alternative would be adding a base-URL parameter to production code purely for tests.
     */
    fun hostRedirectingClient(): OkHttpClient =
        HttpClient.instance.newBuilder()
            .addInterceptor { chain ->
                val original = chain.request()
                val rerouted = original.url.newBuilder()
                    .scheme(server.url("/").scheme)
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(rerouted).build())
            }
            .build()

    override fun close() {
        server.close()
    }
}
