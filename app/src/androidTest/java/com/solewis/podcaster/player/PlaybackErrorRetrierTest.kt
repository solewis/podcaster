package com.solewis.podcaster.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.net.Connectivity
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silentWav
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [PlaybackErrorRetrier] against a real [ExoPlayer] and a real HTTP server whose connection can be
 * cut and restored on demand - the closest an on-device test gets to the reported bug: wifi drops
 * mid-episode, comes back a while later, and playback either recovers or gives up cleanly instead
 * of sitting there forever with `playWhenReady == true` and no explanation.
 *
 * A toggled response code rather than [MockWebServer.shutdown] - shutting down and restarting a
 * server on the same port is exactly the kind of thing that is flaky between runs. Toggling what a
 * server that stays up the whole time answers with is not, and a 503 is a real answer a network
 * failure can hand back (a captive portal, a carrier outage page) - unlike severing the socket
 * outright, which measured out to [androidx.media3.common.PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE]
 * here rather than to any of the codes an actual dropped connection produces, for reasons specific
 * to this fixture's HTTP stack rather than to anything worth reproducing.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackErrorRetrierTest {

    private lateinit var server: MockWebServer
    private lateinit var player: ExoPlayer
    private lateinit var scope: CoroutineScope
    private val serverOnline = AtomicBoolean(true)
    private val alwaysOnline = object : Connectivity {
        override fun isOnline() = true
        override fun isOnWifi() = true
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!serverOnline.get()) {
                    // A bad HTTP status is what a captive portal or a carrier's own outage page
                    // hands back, and - unlike an outright socket death - it lands cleanly on
                    // ERROR_CODE_IO_BAD_HTTP_STATUS rather than on whatever Media3 happens to infer
                    // from a connection dying with zero bytes sent, which measured out as
                    // ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE here: a real code, just not the one
                    // a dropped connection actually produces, and not one this fixture needs to
                    // reproduce to exercise the retrier.
                    return MockResponse().setResponseCode(503)
                }
                val body = silentWav(seconds = 300)
                val rangeHeader = Regex("bytes=([0-9]+)-([0-9]*)")
                val match = request.getHeader("Range")?.let { rangeHeader.find(it) }
                    ?: return MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "audio/wav")
                        .setHeader("Accept-Ranges", "bytes")
                        .setBody(Buffer().write(body))
                val start = match.groupValues[1].toInt().coerceIn(0, body.size - 1)
                val end = match.groupValues[2].toIntOrNull()?.coerceIn(start, body.size - 1)
                    ?: (body.size - 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "audio/wav")
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $start-$end/" + body.size)
                    .setBody(Buffer().write(body.copyOfRange(start, end + 1)))
            }
        }
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        scope.cancel()
        server.shutdown()
    }

    private fun buildPlayer(policy: ReconnectPolicy, log: PlaybackLog? = null): ExoPlayer = onMain {
        ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
            .setLooper(Looper.getMainLooper())
            .build()
            .apply {
                addListener(PlaybackErrorRetrier(this, alwaysOnline, scope, log = log, policy = policy))
            }
    }

    private fun playFromServer() {
        onMain {
            player.setMediaItem(MediaItem.Builder().setMediaId("ep").setUri(server.url("/audio.wav").toString()).build())
            player.prepare()
            player.play()
        }
        awaitPlayer("playing") { onMain { player.isPlaying } }
    }

    private fun tryToPlayWhileOffline() {
        onMain {
            player.setMediaItem(MediaItem.Builder().setMediaId("ep").setUri(server.url("/audio.wav").toString()).build())
            player.prepare()
            player.play()
        }
    }

    @Test
    fun a_dropped_connection_recovers_and_resumes_instead_of_restarting() {
        player = buildPlayer(
            ReconnectPolicy(earlyBackoffMillis = listOf(50L), steadyStateMillis = 100L, giveUpAfterMillis = 5_000L)
        )
        playFromServer()

        serverOnline.set(false)
        // Default DefaultLoadControl buffers up to ~50s of media ahead near-instantly against a
        // local, latency-free fixture - seeking anywhere inside that window would be served
        // entirely from what is already loaded and prove nothing. 200s is well past it, so this
        // forces a fresh request, which is what actually meets the cut connection.
        onMain { player.seekTo(200_000) }

        awaitPlayer("a fatal network error") { onMain { player.playerError } != null }
        assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
        // The bug being fixed: this used to stay true forever with nothing acting on it.
        assertThat(onMain { player.playWhenReady }).isTrue()

        serverOnline.set(true)

        awaitPlayer("recovery, without anything but the retrier touching the player") {
            onMain { player.isPlaying }
        }
        assertThat(onMain { player.playerError }).isNull()
        // Recovered where it left off, not restarted from the top of the episode.
        assertThat(onMain { player.currentPosition }).isAtLeast(195_000)
    }

    @Test
    fun giving_up_stops_retrying_and_leaves_the_error_in_place() {
        // Asserted against the retrier's own log line rather than a fixed sleep plus a request
        // count: a real prepare() failing round-trips through ExoPlayer's own internal
        // LoadErrorHandlingPolicy first, which added multiple seconds of its own retry latency
        // underneath this class's loop when measured here - fixed-delay sampling of the server's
        // request count raced that internal, version-specific cadence and was flaky as a result.
        // RECONNECT_GIVE_UP is only ever written immediately before the loop returns for good, so
        // seeing it is direct proof the loop is done, independent of how long any of that took.
        val log = PlaybackLog(java.io.File.createTempFile("retrier-give-up-test", ".log"))
        player = buildPlayer(
            ReconnectPolicy(earlyBackoffMillis = listOf(100L), steadyStateMillis = 100L, giveUpAfterMillis = 1_000L),
            log = log
        )
        serverOnline.set(false)
        tryToPlayWhileOffline()
        awaitPlayer("a fatal network error") { onMain { player.playerError } != null }

        awaitPlayer("the retrier to give up", timeoutMillis = 20_000) {
            log.snapshot().contains("RECONNECT_GIVE_UP")
        }

        // The server never came back online in this test - a RECOVERED line here would mean the
        // give-up clock got silently reset by a false recovery instead of actually giving up. This
        // is what the fix in PlaybackErrorRetrier.retryUntilRecoveredOrGivenUp is actually for:
        // reproduced, before it, by a poll landing in the BUFFERING window right after a prepare()
        // call cleared the previous error and before the new attempt had failed again.
        assertThat(log.snapshot()).doesNotContain("RECONNECT_RECOVERED")

        // Giving up is this class's own contract; it does not itself stop playWhenReady from
        // being true, so on its own a later, independent error would start an entirely fresh retry
        // cycle - by design, per this class's doc comment, and exactly what
        // PlayerConnection.networkErrorGivenUpAfterWaiting()/pause() exists to end in production.
        // Simulating that here, rather than asserting on this class something only that
        // integration actually guarantees.
        awaitPlayer("the last in-flight attempt to settle before pausing") {
            onMain { player.playbackState } == Player.STATE_IDLE
        }
        onMain { player.pause() }
        Thread.sleep(500)
        assertThat(onMain { player.playbackState }).isEqualTo(Player.STATE_IDLE)
    }
}
