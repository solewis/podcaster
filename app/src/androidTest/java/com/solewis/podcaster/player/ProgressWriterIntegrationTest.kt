package com.solewis.podcaster.player

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import androidx.media3.common.MediaItem
import com.solewis.podcaster.testing.AudioHost
import com.solewis.podcaster.testing.PlayerBackedPlayback
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
    private val otherEpisodeId get() = "$podcastId:other"

    /**
     * Only for the incoming episode in [starting_another_episode_does_not_unfinish_the_one_you_were_on].
     * That test needs the arriving episode's duration to still be unknown when playback moves off
     * the outgoing one, which is the ordinary case over a network and impossible with
     * [silenceSource] - silence prepares synchronously, so its duration is already known and the
     * bug hides behind a plausible-looking answer.
     */
    private val audio = AudioHost()

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
                    ),
                    EpisodeEntity(
                        id = otherEpisodeId,
                        podcastId = podcastId,
                        stableKey = "other",
                        stableKeySource = "guid",
                        title = "Another Episode",
                        enclosureUrl = "https://example.com/other.mp3",
                        feedPosition = 1,
                        chronoIndex = 2,
                        displayNumber = 2,
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
        audio.close()
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
    fun a_seek_records_where_it_arrived_not_where_it_left() {
        // This test used to be named for the opposite behaviour - and did not actually check
        // either. It played, seeked, then *paused*, and the pause write is what satisfied its
        // assertion; the seek's own write was never observed. Meanwhile the code really was
        // recording the position it left, which is what broke "mark as finished" (see below) and
        // what silently lost any seek made just before the app was killed.
        // Seeking while *paused* is what isolates it. While playing, the 5s ticker writes the
        // right position a moment later and a pause writes it immediately, so either one hides a
        // wrong discontinuity write - which is exactly how this went unnoticed.
        loadEpisode(durationMillis = 600_000)

        onMain { player.seekTo(200_000) }

        awaitPlayer("the seek itself to be persisted") {
            (storedEpisode()?.positionMillis ?: 0) >= 200_000
        }
        assertThat(storedEpisode()!!.positionMillis).isAtLeast(200_000)
    }

    @Test
    fun marking_the_loaded_episode_finished_is_not_undone_by_its_own_seek() {
        // The reported bug: "Mark as finished" on the Home feed paused the player and jumped the
        // bar forward, and the row went on showing a progress bar instead of "Finished".
        //
        // PlayedMarker writes the flag and then seeks to the end - and the seek's discontinuity
        // write derived "not complete" from the position it had just left, immediately clearing
        // the flag again. Nothing caught it because the two halves were only ever tested apart:
        // MarkPlayedTest drives PlayedMarker against a FakePlayback whose seekTo appends to a
        // list, so no ProgressWriter ever ran; and this file never involved PlayedMarker. The
        // class's own doc comment calls that interaction "the interesting half".
        loadEpisode(durationMillis = 600_000)
        onMain { player.play() }
        awaitPlayer("some progress to exist") { onMain { player.currentPosition } > 500 }

        runBlocking {
            PlayedMarker(repository, PlayerBackedPlayback(player)).setPlayed(
                episodeId,
                played = true,
                durationMillis = 600_000
            )
        }

        awaitPlayer("the episode to be recorded as played") { storedEpisode()?.isPlayed == true }
        // Held, rather than true for an instant and then reverted by a later write.
        Thread.sleep(1_000)
        assertThat(storedEpisode()!!.isPlayed).isTrue()
        // Honest about its own reach: with the feed's duration equal to the player's, the seek
        // lands on the end, STATE_ENDED writes "complete", and that conflates over the bad
        // discontinuity write - so this passes with either bug present on its own. It is the
        // whole-flow guard; the two tests either side of it are the ones that discriminate.
    }

    @Test
    fun marking_finished_seeks_to_the_players_duration_not_the_feeds() {
        // Feeds routinely understate <itunes:duration>. Seeking to a duration that undershoots the
        // real one by more than CompletionRule's threshold leaves playback short of the end, so
        // the episode never reaches STATE_ENDED and the next write derives "not complete" - which
        // reverts the mark even with the discontinuity fix in place.
        loadEpisode(durationMillis = 600_000)
        onMain { player.play() }
        awaitPlayer("some progress to exist") { onMain { player.currentPosition } > 500 }

        runBlocking {
            PlayedMarker(repository, PlayerBackedPlayback(player)).setPlayed(
                episodeId,
                // Wildly short, as a broken feed would be.
                played = true,
                durationMillis = 60_000
            )
        }

        awaitPlayer("the episode to be recorded as played") { storedEpisode()?.isPlayed == true }
        assertThat(onMain { player.currentPosition }).isAtLeast(500_000)
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

    @Test
    fun starting_another_episode_does_not_unfinish_the_one_you_were_on() {
        // Reported: two episodes marked finished, the third played, a scrub in it, and back on the
        // Home feed the *first* episode showed a full progress bar instead of "Finished".
        //
        // Nothing wrote to it because of the scrub. It was the moment playback moved off it: the
        // discontinuity carries the outgoing episode's final position, but `player.duration` by
        // then describes the incoming one and is TIME_UNSET until that is prepared. A null
        // duration makes CompletionRule answer "not complete", so the played flag was cleared and
        // the end-of-episode position left behind - which is exactly a full bar.
        loadEpisode(durationMillis = 600_000)
        onMain { player.play() }
        awaitPlayer("some progress to exist") { onMain { player.currentPosition } > 500 }
        runBlocking {
            PlayedMarker(repository, PlayerBackedPlayback(player)).setPlayed(
                episodeId, played = true, durationMillis = 600_000
            )
        }
        awaitPlayer("the episode to be finished first") { storedEpisode()?.isPlayed == true }

        // Over HTTP, so the incoming episode's duration is genuinely unknown for the moment the
        // outgoing episode's write lands - measured on device, the discontinuity arrives with
        // `player.duration == TIME_UNSET`, and a null duration is what CompletionRule reads as
        // "not complete".
        val url = audio.url(seconds = 120)
        onMain {
            player.setMediaItem(MediaItem.Builder().setMediaId(otherEpisodeId).setUri(url).build())
            player.prepare()
            player.play()
        }
        awaitPlayer("the other episode to be playing") {
            onMain { player.currentMediaItem?.mediaId } == otherEpisodeId && onMain { player.isPlaying }
        }
        Thread.sleep(1_000)

        assertThat(storedEpisode()!!.isPlayed).isTrue()
        // And no leftover position to draw a bar from.
        assertThat(storedEpisode()!!.positionMillis).isEqualTo(0)
    }

    @Test
    fun leaving_an_episode_partway_keeps_its_resume_point_and_leaves_it_unfinished() {
        // The other side of the same write, and a guard on the fix rather than on the bug: this
        // one passes with or without it, because a transition always arrives before the incoming
        // duration is known. Its job is to catch the fix overreaching - now that the write is
        // handed the *outgoing* episode's real duration, getting that lookup wrong could complete
        // an episode you were only halfway through.
        loadEpisode(durationMillis = 600_000)
        onMain {
            player.play()
            player.seekTo(200_000)
        }
        awaitPlayer("the seek to be persisted") { (storedEpisode()?.positionMillis ?: 0) >= 200_000 }

        onMain {
            // Deliberately shorter than the 200s position being left behind: if the outgoing
            // episode were ever measured against the incoming episode's length, that would read as
            // "complete" and finish an episode nobody finished.
            player.setMediaSource(silenceSource(otherEpisodeId, 120_000))
            player.prepare()
            player.play()
        }
        awaitPlayer("the other episode to be playing") {
            onMain { player.currentMediaItem?.mediaId } == otherEpisodeId && onMain { player.isPlaying }
        }
        Thread.sleep(1_000)

        assertThat(storedEpisode()!!.isPlayed).isFalse()
        assertThat(storedEpisode()!!.positionMillis).isAtLeast(200_000)
    }
}
