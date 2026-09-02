package com.solewis.podcaster.testing

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Shared by the on-device player tests, which all face the same three problems: `ExoPlayer` insists
 * on its own application thread, its work is asynchronous even for silence, and a `SilenceMediaSource`
 * needs coaxing into carrying an episode id.
 */

/**
 * Runs [block] on the main thread and hands back its result.
 *
 * Both the player and the session wrapper throw if touched from anywhere else, and the
 * instrumentation thread a test body runs on is not it.
 */
fun <T> onMain(block: () -> T): T {
    var result: T? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * Polls until [condition] holds. ExoPlayer's work is asynchronous even for silence, so every
 * assertion downstream of it has to wait rather than read immediately.
 */
fun awaitPlayer(what: String, timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return
        Thread.sleep(50)
    }
    throw AssertionError("Timed out after ${timeoutMillis}ms waiting for: $what")
}

/**
 * A seekable source of known duration that reports [mediaId] as its media id.
 *
 * `updateMediaItem`, not `Factory.setTag`: the tag only lands on `localConfiguration`, leaving
 * `SilenceMediaSource`'s own fixed media id in place - which silently makes every id-keyed lookup
 * downstream target something that does not exist.
 */
fun silenceSource(mediaId: String, durationMillis: Long): SilenceMediaSource =
    SilenceMediaSource(durationMillis * 1_000).apply {
        updateMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
    }

/**
 * Serves [seconds] of silent WAV over real HTTP, so a `MediaController` has something playable to
 * point at.
 *
 * HTTP rather than a `file://` URI in the cache directory, which would be simpler: the player's
 * data source chain is `CacheDataSource` over `DefaultHttpDataSource` (see `PlayerFactory`), with
 * no `DefaultDataSource` in it, so a file URI reaches an HTTP source and dies with a
 * `ClassCastException` rather than playing. Production only ever holds `http(s)` enclosure URLs -
 * downloads are served from the cache keyed by that same URL - so nothing is being worked around
 * here beyond the test's own convenience.
 */
class AudioHost : java.io.Closeable {

    private val server = okhttp3.mockwebserver.MockWebServer()

    /** A URL that streams [seconds] of silence. Safe to request more than once. */
    fun url(seconds: Int): String {
        val body = silentWav(seconds)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                okhttp3.mockwebserver.MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/wav")
                    .setBody(okio.Buffer().write(body))
        }
        return server.url("/silence-${seconds}s.wav").toString()
    }

    override fun close() = server.shutdown()
}

/**
 * A real, decodable WAV of [seconds] of silence.
 *
 * [silenceSource] cannot serve the tests that go through a `MediaController`: a controller carries
 * a `MediaItem` with a URI across a binder, not a `MediaSource` object, so those tests need real
 * bytes. Hand-built rather than a checked-in asset - the header is nine fields and the body is
 * zeros, which is less to explain than an opaque binary in the repo.
 */
fun silentWav(seconds: Int): ByteArray {
    val sampleRate = 44_100
    val bytesPerSample = 2
    val dataBytes = seconds * sampleRate * bytesPerSample

    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray())
    header.putInt(36 + dataBytes)
    header.put("WAVE".toByteArray())
    header.put("fmt ".toByteArray())
    header.putInt(16)                              // PCM header size
    header.putShort(1)                             // PCM, uncompressed
    header.putShort(1)                             // mono
    header.putInt(sampleRate)
    header.putInt(sampleRate * bytesPerSample)     // byte rate
    header.putShort(bytesPerSample.toShort())      // block align
    header.putShort(16)                            // bits per sample
    header.put("data".toByteArray())
    header.putInt(dataBytes)

    // Body is zeros: silence in signed 16-bit PCM, and nothing for a decoder to object to.
    return header.array() + ByteArray(dataBytes)
}
