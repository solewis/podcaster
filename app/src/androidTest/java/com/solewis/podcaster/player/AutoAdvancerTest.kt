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
import com.solewis.podcaster.data.repo.QueueRepository
import com.solewis.podcaster.testing.awaitPlayer
import com.solewis.podcaster.testing.onMain
import com.solewis.podcaster.testing.silenceSource
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
 * What happens when an episode finishes: continue with the queue, or stop.
 *
 * Untested until now, which had become the weakest point in the app - [AutoAdvancer]'s gate is what
 * the auto-advance setting and the sleep timer's "end of episode" mode are both *entirely*
 * implemented in terms of. [SleepTimerTest][com.solewis.podcaster.player.SleepTimer] proves the
 * timer disarms itself when asked; nothing proved anybody ever asks.
 *
 * On-device because [AutoAdvancer] takes a real `ExoPlayer` and the event it hangs off -
 * `STATE_ENDED` - is produced by real playback reaching a real end.
 */
@RunWith(AndroidJUnit4::class)
class AutoAdvancerTest {

    private lateinit var db: PodcasterDatabase
    private lateinit var queueRepository: QueueRepository
    private lateinit var player: ExoPlayer
    private lateinit var scope: CoroutineScope
    private var podcastId: Long = 0

    /** Flipped by a test to close the gate; read on every ending, as production reads its settings. */
    private var gateOpen = true

    private val firstEpisode get() = "$podcastId:one"
    private val secondEpisode get() = "$podcastId:two"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PodcasterDatabase::class.java
        ).build()
        val episodes = EpisodeRepository(db.episodeDao(), db.podcastDao())
        queueRepository = QueueRepository(db.queueDao(), episodes)
        // Main-dispatched: the coroutine this launches calls back into the player, which throws if
        // touched off its application thread. Production passes the service's lifecycleScope, which
        // is main-dispatched for the same reason.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        runBlocking {
            podcastId = db.podcastDao().insert(
                PodcastEntity(feedUrl = "https://example.com/f.xml", title = "Show", subscribedAt = 1L)
            )
            db.episodeDao().insertNew(listOf(episode("one", 1), episode("two", 2)))
        }

        onMain {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext())
                .setLooper(Looper.getMainLooper())
                .build()
            player.addListener(AutoAdvancer(player, queueRepository, scope) { gateOpen })
        }
    }

    @After
    fun tearDown() {
        onMain { player.release() }
        scope.cancel()
        db.close()
    }

    private fun episode(key: String, chronoIndex: Int) = EpisodeEntity(
        id = "$podcastId:$key",
        podcastId = podcastId,
        stableKey = key,
        stableKeySource = "guid",
        title = "Episode $chronoIndex",
        enclosureUrl = "https://example.com/$key.mp3",
        feedPosition = chronoIndex,
        chronoIndex = chronoIndex,
        displayNumber = chronoIndex,
        firstSeenAt = 1L
    )

    /**
     * Starts a short silence playing, so it reaches its own end shortly after - which is how the
     * real `STATE_ENDED` arrives.
     *
     * Deliberately does not wait for `STATE_ENDED` itself. When advancing does happen it replaces
     * the media item immediately, moving the player straight out of `ENDED` into buffering the next
     * one, so waiting for that state is a race against the very thing under test. Each test waits
     * for its own outcome instead.
     */
    private fun playShortEpisode(durationMillis: Long = 1_500) {
        onMain {
            player.setMediaSource(silenceSource(firstEpisode, durationMillis))
            player.prepare()
            player.play()
        }
        awaitPlayer("playback started") { onMain { player.isPlaying } }
    }

    /**
     * Waits out the episode without advancing. Safe to wait on `STATE_ENDED` here precisely because
     * nothing is expected to move the player off it.
     */
    private fun awaitEndedWithoutAdvancing() =
        awaitPlayer("the episode ended") { onMain { player.playbackState } == Player.STATE_ENDED }

    /**
     * The next episode's `enclosureUrl` is fictional, so preparing it will fail - but
     * `currentMediaItem` reflects the `setMediaItem` immediately, which is the part [AutoAdvancer]
     * is responsible for.
     */
    private fun awaitAdvancedTo(episodeId: String) =
        awaitPlayer("advanced to $episodeId") {
            onMain { player.currentMediaItem?.mediaId } == episodeId
        }

    private fun loadWithoutPlaying() {
        onMain {
            player.setMediaSource(silenceSource(firstEpisode, 600_000))
            player.prepare()
        }
        awaitPlayer("player ready") { onMain { player.playbackState } == Player.STATE_READY }
    }

    private fun queuedIds() = runBlocking { db.queueDao().getAllOrdered().map { it.episodeId } }

    @Test
    fun finishing_an_episode_starts_the_next_one_in_the_queue() {
        runBlocking { queueRepository.enqueue(secondEpisode) }

        playShortEpisode()

        awaitAdvancedTo(secondEpisode)
        // Taken off the queue, not left on it - otherwise it would play again next time.
        awaitPlayer("the queue was consumed") { queuedIds().isEmpty() }
    }

    @Test
    fun a_closed_gate_stops_at_the_end_and_leaves_the_queue_alone() {
        runBlocking { queueRepository.enqueue(secondEpisode) }
        gateOpen = false

        playShortEpisode()
        awaitEndedWithoutAdvancing()

        // This single check is what both the auto-advance setting and the sleep timer's end-of-episode
        // mode rely on entirely. If it ever stops being consulted, both features silently stop working
        // while every one of their own tests keeps passing.
        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo(firstEpisode)
        assertThat(onMain { player.isPlaying }).isFalse()
        // Intact, so turning the setting back on - or just opening the app tomorrow - still has it.
        assertThat(queuedIds()).containsExactly(secondEpisode)
    }

    @Test
    fun the_gate_is_consulted_at_each_ending_rather_than_captured_once() {
        runBlocking { queueRepository.enqueue(secondEpisode) }
        gateOpen = false
        playShortEpisode()
        awaitEndedWithoutAdvancing()
        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo(firstEpisode)

        // Reopened between endings, exactly as arming a sleep timer mid-episode does.
        gateOpen = true
        playShortEpisode()

        awaitAdvancedTo(secondEpisode)
    }

    @Test
    fun finishing_with_nothing_queued_and_nothing_unplayed_left_stays_put() {
        // The queue is empty and the only later episode is already played, so the show has nothing
        // to offer either.
        runBlocking {
            db.episodeDao().setProgress(secondEpisode, positionMillis = 0, isPlayed = true, now = 1L)
        }

        playShortEpisode()
        awaitEndedWithoutAdvancing()

        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo(firstEpisode)
    }

    @Test
    fun pausing_partway_through_is_not_an_ending() {
        runBlocking { queueRepository.enqueue(secondEpisode) }
        loadWithoutPlaying()

        onMain {
            player.play()
            player.seekTo(300_000)
        }
        awaitPlayer("playing past the seek") { onMain { player.currentPosition } >= 300_000 }
        onMain { player.pause() }

        // Only STATE_ENDED advances. Treating a pause as an ending would hijack the queue every time
        // someone stopped mid-episode.
        assertThat(onMain { player.currentMediaItem?.mediaId }).isEqualTo(firstEpisode)
        assertThat(queuedIds()).containsExactly(secondEpisode)
    }
}
