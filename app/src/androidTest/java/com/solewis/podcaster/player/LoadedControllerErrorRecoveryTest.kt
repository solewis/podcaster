package com.solewis.podcaster.player

import android.content.ComponentName
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.MainActivity
import com.solewis.podcaster.PodcasterApp
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.net.Connectivity
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.inMemoryTestDatabase
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silentWav
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
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
 * [PlayerConnection.loadedController]'s "a lingering error must not make the next command a silent
 * no-op" fix, against the real [PlaybackService] and its own real, automatic [PlaybackErrorRetrier]
 * - which is exactly why [Connectivity] is faked to always answer offline here: that retrier reads
 * the real `AndroidConnectivity`, which reports the emulator's actual network as fine regardless of
 * what this test's own fixture server is doing, and would otherwise recover the session on its own
 * schedule and make it impossible to tell whether a recovery came from that background retry or
 * from the fix under test. With it pinned offline, the automatic retrier can only ever log that it
 * is still offline - so any recovery seen here has to have come from the manual command.
 *
 * Harness copied from [SessionSyncTest], which already solved binding a real `MediaController` to a
 * real [PlaybackService] in a test.
 */
@RunWith(AndroidJUnit4::class)
class LoadedControllerErrorRecoveryTest {

    private val app get() = ApplicationProvider.getApplicationContext<PodcasterApp>()
    private val context: android.content.Context get() = app
    /**
     * Deliberately never closed. [PlaybackService] is a real, process-wide singleton that captures
     * `container.episodeRepository` once, in its own `onCreate()`, whichever test happens to start
     * it first in a full suite run - not per test. If this class is that first test and then closes
     * its own database, [PodcastLibraryTree.resolveForPlayback] (which every `setMediaItem` call
     * goes through, not only Android Auto's) queries a closed database on the *next* test class to
     * touch the service, and the failed lookup silently drops that test's own media item metadata.
     * Reproduced by running this class immediately before `SessionSyncTest`. Same hazard
     * `SessionSyncTest`'s own doc comment already names for the app-level container reference; this
     * is the same thing one layer further down, in the service's own captured repository.
     */
    private var db: PodcasterDatabase? = null
    private val token get() = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var external: MediaController
    private var connection: PlayerConnection? = null

    private lateinit var server: MockWebServer
    private val serverOnline = AtomicBoolean(true)
    private val episodeId = "1:ep"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!serverOnline.get()) return MockResponse().setResponseCode(503)
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

        val database = inMemoryTestDatabase(app)
        db = database
        app.installContainer(
            AppContainer(
                app,
                database = database,
                connectivity = object : Connectivity {
                    override fun isOnline() = false
                    override fun isOnWifi() = false
                }
            )
        )

        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.moveToState(Lifecycle.State.RESUMED)
        scenario?.onActivity { activity ->
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val item = MediaItem.Builder()
            .setMediaId(episodeId)
            .setUri(server.url("/audio.wav").toString())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("An Episode").build())
            .build()

        external = runBlocking(Dispatchers.Main) {
            MediaController.Builder(context, token).buildAsync().await().also {
                it.setMediaItem(item, 0)
                it.prepare()
                it.play()
            }
        }
        awaitPlayer("the session to be playing", timeoutMillis = 30_000) {
            onMain { external.isPlaying }
        }
    }

    @After
    fun tearDown() {
        onMain {
            external.stop()
            external.release()
            connection?.release()
        }
        scenario?.close()
        server.shutdown()
    }

    private fun coldApp(): PlayerConnection =
        onMain { PlayerConnection(context) }.also { connection = it }

    @Test
    fun a_manual_command_clears_a_lingering_error_that_the_offline_background_retrier_cannot() {
        serverOnline.set(false)
        // Past the default ~50s read-ahead buffer, to force a fresh request against the now-503ing
        // server rather than being served from what is already loaded.
        onMain { external.seekTo(200_000) }
        awaitPlayer("a fatal error on the real session") { onMain { external.playerError } != null }

        serverOnline.set(true)

        // With Connectivity faked offline, PlaybackService's own automatic PlaybackErrorRetrier
        // can only log RECONNECT_STILL_OFFLINE - it never calls prepare() on its own. This is the
        // isolation the whole test depends on: proof the error would sit here otherwise, so
        // whatever clears it next can only be the manual command below.
        Thread.sleep(1_500)
        assertThat(onMain { external.playerError }).isNotNull()

        val playback = coldApp()
        runBlocking(Dispatchers.Main) { playback.seekTo(210_000) }

        // Before the fix, play()/seekTo() left a fatal error untouched - only prepare() clears one,
        // and nothing called it. Recovering here is only possible because loadedController() now
        // calls prepare() itself when it finds a lingering error.
        awaitPlayer("the manual command to actually clear the error", timeoutMillis = 10_000) {
            onMain { external.playerError } == null
        }
        awaitPlayer("playback to actually resume") { onMain { external.isPlaying } }
    }
}
