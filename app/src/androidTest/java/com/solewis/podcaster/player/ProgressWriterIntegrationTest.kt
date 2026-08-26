package com.solewis.podcaster.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silenceSource
import com.solewis.podcaster.data.db.PodcasterDatabase
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.repo.EpisodeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The resume mechanism, on a real [ExoPlayer] writing to a real database.
 *
 * This is the half of the headline feature that cannot run on the JVM - ExoPlayer needs real
 * decoders - and it is the half where a silent failure is worst: a wrong or missing position looks
 * exactly like "it just started over", which is easy to miss in a quick manual check and destroys
 * the only reason this app exists over Spotify.
 *
 * Silence rather than a real episode: it prepares instantly, needs no network, and has a known
 * duration, which is all these assertions depend on.
 */
@RunWith(AndroidJUnit4::class)
class ProgressWriterIntegrationTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var repository: EpisodeRepository
    private lateinit var player: ExoPlayer
    private lateinit var progressWriter: ProgressWriter
    private lateinit var scope: CoroutineScope
    private var podcastId: Long = 0
    private val episodeId get() = "$podcastId:ep"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PodcasterDatabase::class.java
        ).build()
        repository = EpisodeRepository(db.episodeDao(), db.podcastDao())
        // Main, not the default dispatcher: ProgressWriter's ticker reads player state directly,
        // and ExoPlayer throws if touched off its application thread. Production passes the
        // service's lifecycleScope, which is main-dispatched for the same reason.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        runBlocking {
            podcastId = db.podcastDao().insert(
                PodcastEntity(feedUrl = "https://example.com/f.xml", title = "Show", subscribedAt = 1L)
            )
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
                        firstSeenAt = 1L
                    )
                )
            )
        }

        onMain {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                .setLooper(Looper.getMainLooper())
                .build()
            progressWriter = ProgressWriter(player, repository, scope)
            player.addListener(progressWriter)
        }
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        scope.cancel()
        db.close()
    }

    private fun loadEpisode(durationMillis: Long) {
        onMain {
            player.setMediaSource(silenceSource(episodeId, durationMillis))
            player.prepare()
        }
        awaitPlayer("player ready") { onMain { player.playbackState } == Player.STATE_READY }
        awaitPlayer("the player is reporting our episode") {
            onMain { player.currentMediaItem?.mediaId } == episodeId
        }
    }

    private fun storedEpisode() = runBlocking { db.episodeDao().getById(episodeId) }

    @Test
    fun pausing_partway_through_persists_where_you_were() {
        loadEpisode(durationMillis = 600_000)
        onMain {
            player.play()
            player.seekTo(120_000)
        }
        awaitPlayer("playing") { onMain { player.isPlaying } }

        onMain { player.pause() }

        // Written on pause rather than only by the 5s ticker, so quitting right after pausing
        // cannot lose up to five seconds of position.
        awaitPlayer("position persisted") { (storedEpisode()?.positionMillis ?: 0) > 0 }
        val stored = storedEpisode()!!
        assertThat(stored.positionMillis).isAtLeast(120_000)
        assertThat(stored.isPlayed).isFalse()
        // Stamped so the resume pill has an anchor to find this episode by.
        assertThat(stored.lastPlayedAt).isNotNull()
    }

    @Test
    fun a_seek_records_the_position_it_left_rather_than_the_one_it_arrived_at() {
        loadEpisode(durationMillis = 600_000)
        onMain {
            player.play()
            player.seekTo(200_000)
        }
        awaitPlayer("playing past the seek") { onMain { player.currentPosition } >= 200_000 }

        onMain { player.pause() }

        awaitPlayer("position persisted") { (storedEpisode()?.positionMillis ?: 0) >= 200_000 }
    }

    @Test
    fun playing_to_the_end_marks_the_episode_played_and_clears_the_position() {
        loadEpisode(durationMillis = 2_000)
        onMain { player.play() }

        awaitPlayer("episode marked played") { storedEpisode()?.isPlayed == true }

        // Position resets so the next tap starts it over rather than resuming at the very end.
        assertThat(storedEpisode()?.positionMillis).isEqualTo(0)
        assertThat(storedEpisode()?.playedAt).isNotNull()
    }

    @Test
    fun the_real_duration_is_backfilled_over_whatever_the_feed_claimed() {
        loadEpisode(durationMillis = 300_000)

        // Feeds frequently omit or misstate duration; the player is the only reliable source, and
        // every "Nm left" label depends on it.
        awaitPlayer("duration backfilled") { storedEpisode()?.durationMillis != null }
        val stored = storedEpisode()!!
        assertThat(stored.durationMillis).isWithin(2_000).of(300_000)
        assertThat(stored.durationIsExact).isTrue()
    }

    @Test
    fun the_final_flush_captures_the_position_even_with_no_pause_first() {
        loadEpisode(durationMillis = 600_000)
        onMain {
            player.play()
            player.seekTo(90_000)
        }
        awaitPlayer("playing past the seek") { onMain { player.currentPosition } >= 90_000 }

        // What PlaybackService.onDestroy does: the process is going away, so there is no chance to
        // wait for a coroutine.
        onMain { progressWriter.flushBlocking() }

        assertThat(storedEpisode()!!.positionMillis).isAtLeast(90_000)
    }

}
