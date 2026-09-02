package com.solewis.podcaster.player

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.AppContainer
import com.solewis.podcaster.MainActivity
import com.solewis.podcaster.PodcasterApp
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.testing.AudioHost
import com.solewis.podcaster.testing.inMemoryTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app agreeing with the notification about whether anything is playing.
 *
 * Reported from the phone: play from the pull-down notification while the app is closed, then tap
 * the notification to open the app - and the app showed the episode paused while it was audibly
 * playing. Two things caused it, and both are pinned below. `MediaController` only reports
 * *changes*, so a controller built after playback started is told nothing at all; and the
 * controller is built lazily, on the first command the user issues, so on a launch where they
 * issue none nothing ever connected to be told anything in the first place.
 *
 * On-device against the real [PlaybackService] because the bug lives entirely in what a real
 * `MediaController` does and does not report on connection - a fake would report whatever it was
 * written to report, which is the assumption that was wrong.
 */
@RunWith(AndroidJUnit4::class)
class SessionSyncTest {

    private val app get() = ApplicationProvider.getApplicationContext<PodcasterApp>()
    private val context: android.content.Context get() = app

    /**
     * A container this test owns, installed before anything binds the service.
     *
     * Not optional housekeeping: `MainActivitySmokeTest` installs its own container and closes that
     * database in its teardown without putting the original back, so by the time this class runs in
     * the full suite `app.container` can be pointing at a shut database. `PlaybackService` reads its
     * repositories straight off the container, and a session callback that throws leaves
     * `onSetMediaItems` returning a failed future - so the media items never land and playback never
     * starts, which shows up here as "the session never played" with no error of its own.
     */
    private var db: PodcasterDatabase? = null
    private val token get() = SessionToken(context, ComponentName(context, PlaybackService::class.java))

    private val audio = AudioHost()

    /**
     * Only here to put the app's process in the foreground.
     *
     * Android 15 hardened audio focus: a request from a process that is not foreground is denied
     * outright (`AS.HardeningEnforcer: Focus request DENIED ... procState:4`), and Media3's
     * `AudioFocusManager` then forces `playWhenReady` back to false - so the session buffers to
     * READY and simply never plays. Nothing to do with what is under test, and not a thing real
     * use runs into: by the time a person presses play the app or its foreground service is there.
     */
    private var scenario: ActivityScenario<MainActivity>? = null

    /** Stands in for the notification, the car, or a headset button: playback with no app involved. */
    private lateinit var external: MediaController
    private var connection: PlayerConnection? = null

    private val episodeId = "1:ep"

    @Before
    fun setUp() {
        val database = inMemoryTestDatabase(app)
        db = database
        app.installContainer(AppContainer(app, database = database))

        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario?.moveToState(Lifecycle.State.RESUMED)
        scenario?.onActivity { activity ->
            // Explicit rather than assumed. Run on its own this class is fine, but partway through
            // the whole suite the screen has had time to sleep and the keyguard to appear, and a
            // process behind the keyguard is not foreground - so focus is denied and the session
            // sits at READY without ever playing.
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val item = MediaItem.Builder()
            .setMediaId(episodeId)
            .setUri(audio.url(seconds = 30))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle("An Episode").setArtist("Radiolab").build()
            )
            .build()

        external = runBlocking(Dispatchers.Main) {
            MediaController.Builder(context, token).buildAsync().await().also {
                it.setMediaItem(item, 5_000)
                it.prepare()
                it.play()
            }
        }
        // Generous, and only here: the first test in the class pays for a cold service start -
        // opening the download and stream caches - on top of connecting and buffering, and 10s
        // was not reliably enough for that one run.
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
        audio.close()
        db?.close()
    }

    /** A cold-started app: nothing has been asked of playback yet. */
    private fun coldApp(): PlayerConnection =
        onMain { PlayerConnection(context) }.also { connection = it }

    @Test
    fun a_controller_that_arrives_after_playback_started_is_told_nothing_by_its_listener() {
        // The mechanism, in isolation - this is why reading the state on connection is the fix
        // rather than a belt-and-braces extra. A listener added to an already-playing session
        // hears no onIsPlayingChanged, because from Media3's point of view nothing changed.
        var reported: Boolean? = null
        val late = runBlocking(Dispatchers.Main) {
            MediaController.Builder(context, token).buildAsync().await().also { controller ->
                controller.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        reported = isPlaying
                    }
                })
            }
        }
        try {
            Thread.sleep(1_000)
            assertThat(reported).isNull()
            // The truth was sitting there to be read the whole time.
            assertThat(onMain { late.isPlaying }).isTrue()
        } finally {
            onMain { late.release() }
        }
    }

    @Test
    fun a_cold_app_syncing_with_a_live_session_reports_it_as_playing() {
        val playback = coldApp()

        val adopted = runBlocking(Dispatchers.Main) { playback.syncWithSession() }

        assertThat(adopted).isTrue()
        awaitPlayer("the app to agree that it is playing") { playback.state.value.isPlaying }
        assertThat(playback.state.value.episodeId).isEqualTo(episodeId)
    }

    @Test
    fun it_adopts_the_episode_the_session_holds_rather_than_an_empty_player() {
        val playback = coldApp()

        runBlocking(Dispatchers.Main) { playback.syncWithSession() }

        // Title and show come off the session's metadata, which is what the mini player draws -
        // an adopted state missing them would look like a blank bar over audible playback.
        with(playback.state.value) {
            assertThat(title).isEqualTo("An Episode")
            assertThat(podcastTitle).isEqualTo("Radiolab")
        }
    }

    @Test
    fun the_position_comes_from_the_session_not_from_zero() {
        val playback = coldApp()

        runBlocking(Dispatchers.Main) { playback.syncWithSession() }

        // Started at 5s and playing, so anything near zero means the scrubber was reset to the
        // top of the episode - the same class of wrongness as losing the saved position.
        assertThat(playback.progress.value.positionMillis).isAtLeast(4_000)
    }

    @Test
    fun the_clock_starts_ticking_after_a_sync() {
        val playback = coldApp()

        runBlocking(Dispatchers.Main) { playback.syncWithSession() }

        // The progress ticker only runs while the state says playing, so a state stuck on paused
        // froze the on-screen clock as well as the play button. One consequence, two symptoms.
        val first = playback.progress.value.positionMillis
        awaitPlayer("the clock to advance") { playback.progress.value.positionMillis > first }
    }
}
