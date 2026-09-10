package com.solewis.podcaster.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.repo.EpisodeRepository
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silenceSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the car sees when it reconnects.
 *
 * The bug this exists for: after the car had been off long enough for the playback service to die,
 * reconnecting built a session over an empty player. Android Auto reads the *session*, not the
 * app's own restored UI state, so its widget had nothing to show and fell back to "tap to open" -
 * while the phone, reading its own state, still displayed the episode.
 *
 * On-device with a real `ExoPlayer` because the whole assertion is about what the player reports
 * afterwards, and because "seeded but not prepared" is a real player state a fake would only
 * pretend to have.
 */
@RunWith(AndroidJUnit4::class)
class SessionSeederTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var seeder: SessionSeeder
    private lateinit var player: ExoPlayer
    private var podcastId: Long = 0

    private val episodeId get() = "$podcastId:ep"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PodcasterDatabase::class.java
        ).build()
        seeder = SessionSeeder(EpisodeRepository(db.episodeDao(), db.podcastDao()))
        runBlocking {
            podcastId = db.podcastDao().insert(
                PodcastEntity(feedUrl = "https://example.com/f.xml", title = "Show", subscribedAt = 1L)
            )
        }
        onMain {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                .setLooper(Looper.getMainLooper())
                .build()
        }
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        db.close()
    }

    private fun insertEpisode(positionMillis: Long, lastPlayedAt: Long?) = runBlocking {
        db.episodeDao().insertNew(
            listOf(
                EpisodeEntity(
                    id = episodeId,
                    podcastId = podcastId,
                    stableKey = "ep",
                    stableKeySource = "guid",
                    title = "An Episode",
                    enclosureUrl = "https://example.com/ep.mp3",
                    feedPosition = 0,
                    chronoIndex = 1,
                    displayNumber = 1,
                    positionMillis = positionMillis,
                    lastPlayedAt = lastPlayedAt,
                    firstSeenAt = 1L
                )
            )
        )
    }

    private fun seed() = runBlocking { onMain { runBlocking { seeder.seed(player) } } }

    @Test
    fun a_reconnecting_session_finds_the_last_played_episode_waiting_in_it() {
        insertEpisode(positionMillis = 90_000, lastPlayedAt = 5_000)

        seed()

        // The media id is what Android Auto reads to label its widget - an empty player here is
        // exactly the "tap to open" state the car was showing.
        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo(episodeId)
        assertThat(onMain { player.mediaMetadata.title }).isEqualTo("An Episode")
    }

    @Test
    fun it_resumes_from_where_you_stopped_rather_than_the_beginning() {
        insertEpisode(positionMillis = 90_000, lastPlayedAt = 5_000)

        seed()

        assertThat(onMain { player.currentPosition }).isEqualTo(90_000)
    }

    @Test
    fun seeding_prepares_so_the_transport_controls_are_not_dead() {
        insertEpisode(positionMillis = 90_000, lastPlayedAt = 5_000)

        seed()

        // Not STATE_IDLE. An earlier version left it idle on purpose, to fetch nothing until play
        // was pressed - which cost the car its skip buttons entirely, since an idle player has no
        // timeline and so offers no seek commands for those buttons to be bound to.
        assertThat(onMain { player.playbackState }).isNotEqualTo(Player.STATE_IDLE)
    }

    @Test
    fun an_idle_player_offers_no_seek_commands_which_is_why_preparing_matters() {
        // The mechanism itself, on a source that really loads. Media3 disables a CommandButton
        // whose player command is unavailable, so this is the difference between a car whose
        // forward/back work and one where they do nothing at all.
        onMain { player.setMediaSource(silenceSource("ep", 600_000)) }
        assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SEEK_BACK) }).isFalse()
        assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) }).isFalse()

        onMain { player.prepare() }
        awaitPlayer("ready") { onMain { player.playbackState } == Player.STATE_READY }

        assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SEEK_BACK) }).isTrue()
        assertThat(onMain { player.isCommandAvailable(Player.COMMAND_SEEK_FORWARD) }).isTrue()
    }

    @Test
    fun a_library_that_has_never_been_played_seeds_nothing() {
        insertEpisode(positionMillis = 0, lastPlayedAt = null)

        seed()

        assertThat(onMain { player.currentMediaItem }).isNull()
    }

    @Test
    fun it_never_displaces_something_already_loaded() {
        insertEpisode(positionMillis = 90_000, lastPlayedAt = 5_000)
        onMain {
            player.setMediaSource(silenceSource("already-playing", 600_000))
            player.prepare()
        }

        seed()

        // The service outlives individual controllers, so the database can answer after playback
        // has already moved on. Seeding over it would yank the episode out from under the listener.
        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo("already-playing")
    }
}
