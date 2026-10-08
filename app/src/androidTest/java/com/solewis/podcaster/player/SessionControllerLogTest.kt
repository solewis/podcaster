package com.solewis.podcaster.player

import android.content.Context
import android.os.Looper
import android.view.KeyEvent
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.data.repo.PodcastRepository
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.inMemoryTestDatabase
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silenceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The log saying who is talking to the session - see the comment on these callbacks in
 * [PodcastLibrarySessionCallback] for the drive that needed it.
 *
 * Both kinds of caller, through a real session: a Media3 [MediaController], which is what the app
 * itself uses, and a platform `android.media.session.MediaController`, which is the route Android
 * Auto, Bluetooth and the system UI take. The second is the one that matters - it is where a car's
 * presses would arrive - and the one a fake could not stand in for, since what Media3 reports about
 * it is exactly what is being pinned.
 */
@RunWith(AndroidJUnit4::class)
class SessionControllerLogTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var db: PodcasterDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var logFile: File
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession

    @Before
    fun setUp() {
        db = inMemoryTestDatabase(context)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        logFile = File.createTempFile("session-log", ".txt")
        val episodes = EpisodeRepository(db.episodeDao(), db.podcastDao())
        val callback = PodcastLibrarySessionCallback(
            podcastRepository = PodcastRepository(db.podcastDao()),
            episodeRepository = episodes,
            queueRepository = QueueRepository(db.queueDao(), episodes),
            scope = scope,
            log = PlaybackLog(logFile)
        )
        onMain {
            player = ExoPlayer.Builder(context).setLooper(Looper.getMainLooper()).build()
            player.setMediaSource(silenceSource("ep", 600_000))
            player.prepare()
            // Its own id: one per process, and the real PlaybackService may hold the default one.
            session = MediaLibrarySession.Builder(context, TimedSkipPlayer(player), callback)
                .setId("session-log-test")
                .build()
        }
    }

    @After
    fun tearDown() {
        onMain {
            session.release()
            player.release()
        }
        scope.cancel()
        db.close()
        logFile.delete()
    }

    @Test
    fun a_controller_arriving_and_leaving_is_recorded() = runBlocking {
        val controller = MediaController.Builder(context, session.token).buildAsync().await()
        awaitLog("the connection") { "CONTROLLER_CONNECT from=${context.packageName}" in it }

        onMain { controller.release() }

        awaitLog("the disconnection") { "CONTROLLER_DISCONNECT from=${context.packageName}" in it }
    }

    @Test
    fun a_command_is_recorded_with_who_sent_it() = runBlocking {
        val controller = MediaController.Builder(context, session.token).buildAsync().await()

        onMain { controller.seekToNext() }

        awaitLog("the command") { "SESSION_COMMAND command=SEEK_TO_NEXT from=${context.packageName}" in it }
        onMain { controller.release() }
    }

    @Test
    fun a_press_through_the_platform_session_is_recorded_as_such() {
        // The route a car's on-screen skip button takes.
        val platform = android.media.session.MediaController(context, session.platformToken)

        platform.transportControls.skipToNext()

        awaitLog("the platform command") { log ->
            log.lines().any { "SESSION_COMMAND command=SEEK_TO_NEXT" in it && " legacy" in it }
        }
    }

    @Test
    fun a_hardware_button_is_recorded_with_its_key() {
        // The route a steering-wheel button takes.
        val platform = android.media.session.MediaController(context, session.platformToken)

        platform.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))

        awaitLog("the media button") { "MEDIA_BUTTON key=KEYCODE_MEDIA_NEXT action=0" in it }
    }

    private fun awaitLog(what: String, condition: (String) -> Boolean) {
        try {
            awaitPlayer(what) { condition(logFile.readText()) }
        } catch (e: AssertionError) {
            throw AssertionError("${e.message}\nLog was:\n${logFile.readText()}", e)
        }
    }
}
