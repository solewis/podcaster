package com.solewis.podcaster.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.EpisodeListItem
import kotlinx.coroutines.flow.Flow

@Dao
interface EpisodeDao {

    /**
     * Inserts only genuinely new episodes; an id collision (an episode already known from a
     * previous refresh) is silently ignored rather than overwritten. This is half of how a
     * refresh avoids clobbering playback progress - see [updateMetadata] for the other half, and
     * the warning on [EpisodeEntity] for why this split exists at all.
     *
     * @return the SQLite rowid for each input row, in order; `-1` for a row that was skipped
     * because its id already existed. Useful for logging how many episodes a refresh actually
     * added without needing a second query.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(episodes: List<EpisodeEntity>): List<Long>

    /**
     * Updates everything a feed refresh can legitimately change about an already-known episode -
     * and nothing else. Deliberately touches no playback column (`positionMillis`, `isPlayed`,
     * `lastPlayedAt`, `playedAt`). `durationMillis` is the one exception with conditional logic:
     * once ExoPlayer has reported a real duration ([durationIsExact] on the existing row), the
     * feed's `<itunes:duration>` value - which may be missing or wrong - no longer overrides it.
     */
    @Query(
        """
        UPDATE episodes SET
            title = :title,
            descriptionHtml = :descriptionHtml,
            pubDateMillis = :pubDateMillis,
            enclosureUrl = :enclosureUrl,
            enclosureBytes = :enclosureBytes,
            enclosureMimeType = :enclosureMimeType,
            artworkUrl = :artworkUrl,
            itunesEpisodeNumber = :itunesEpisodeNumber,
            itunesSeason = :itunesSeason,
            episodeType = :episodeType,
            webPageUrl = :webPageUrl,
            feedPosition = :feedPosition,
            chronoIndex = :chronoIndex,
            displayNumber = :displayNumber,
            durationMillis = CASE WHEN durationIsExact THEN durationMillis ELSE :durationMillis END
        WHERE id = :id
        """
    )
    suspend fun updateMetadata(
        id: String,
        title: String,
        descriptionHtml: String?,
        pubDateMillis: Long?,
        enclosureUrl: String,
        enclosureBytes: Long?,
        enclosureMimeType: String?,
        artworkUrl: String?,
        itunesEpisodeNumber: Int?,
        itunesSeason: Int?,
        episodeType: String,
        webPageUrl: String?,
        feedPosition: Int,
        chronoIndex: Int?,
        displayNumber: Int?,
        durationMillis: Long?
    )

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun getById(id: String): EpisodeEntity?

    @Query("SELECT id FROM episodes WHERE podcastId = :podcastId")
    suspend fun getAllIdsForPodcast(podcastId: Long): List<String>

    /**
     * The episode auto-advance (and the manual "skip to next" button) plays when nothing sits
     * ahead of it in the personal queue - the next unplayed `full` episode of the *same show*,
     * by [com.solewis.podcaster.data.db.entity.EpisodeEntity.chronoIndex] regardless of the
     * show's current sort order, matching [com.solewis.podcaster.domain.JumpTargetResolver]'s own
     * NEXT rule.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId AND episodeType = 'full' AND isPlayed = 0 AND chronoIndex > :afterChronoIndex
        ORDER BY chronoIndex ASC
        LIMIT 1
        """
    )
    suspend fun findNextUnplayed(podcastId: Long, afterChronoIndex: Int): EpisodeEntity?

    @Query(
        """
        SELECT id, podcastId, title, pubDateMillis, durationMillis, displayNumber, chronoIndex,
               episodeType, artworkUrl, positionMillis, isPlayed, lastPlayedAt
        FROM episodes
        WHERE podcastId = :podcastId
        ORDER BY (chronoIndex IS NULL) ASC, chronoIndex DESC
        """
    )
    fun observeListForPodcast(podcastId: Long): Flow<List<EpisodeListItem>>

    /**
     * One-shot full-entity snapshot of a show's episodes (unlike [observeListForPodcast], which
     * is both reactive and a lightweight projection missing `enclosureUrl`) - needed by the
     * Android Auto browse tree, which has to build real playable [androidx.media3.common.MediaItem]s
     * with a URI rather than just render text/duration.
     */
    @Query(
        """
        SELECT * FROM episodes
        WHERE podcastId = :podcastId
        ORDER BY (chronoIndex IS NULL) ASC, chronoIndex DESC
        """
    )
    suspend fun getAllForPodcast(podcastId: Long): List<EpisodeEntity>

    /**
     * One-shot full-entity snapshot across every subscription, newest-published first - the
     * same ordering as [observeAllEpisodes] (the Home feed), but with `enclosureUrl` included
     * so the Android Auto browse tree's "Episodes" tab can build real playable
     * [androidx.media3.common.MediaItem]s rather than just render text/duration.
     */
    @Query("SELECT * FROM episodes ORDER BY (pubDateMillis IS NULL) ASC, pubDateMillis DESC")
    suspend fun getAllAcrossPodcasts(): List<EpisodeEntity>

    /**
     * The episode this show was most recently played, if any. Backed by the
     * `(podcastId, lastPlayedAt)` index, so this is a single index range scan rather than a table
     * sweep even on a show with thousands of episodes.
     *
     * Not currently called: the Show screen's jump target ([com.solewis.podcaster.domain.JumpTargetResolver])
     * computes this from the full episode list it already holds in memory, which is simpler and
     * guaranteed consistent with what's on screen. This query earns its keep once the Library
     * screen's "Continue listening" row exists (a later phase), which needs a cheap per-podcast
     * answer *without* loading every episode of every show.
     */
    @Query(
        """
        SELECT id, podcastId, title, pubDateMillis, durationMillis, displayNumber, chronoIndex,
               episodeType, artworkUrl, positionMillis, isPlayed, lastPlayedAt
        FROM episodes
        WHERE podcastId = :podcastId AND lastPlayedAt IS NOT NULL
        ORDER BY lastPlayedAt DESC
        LIMIT 1
        """
    )
    suspend fun getLastListened(podcastId: Long): EpisodeListItem?

    /**
     * Removes episodes that vanished from a refreshed feed - but only ones with no listen
     * history. Publishers pull episodes; losing that history to someone else's takedown would be
     * worse than leaving an occasional orphaned row behind.
     */
    @Query("DELETE FROM episodes WHERE podcastId = :podcastId AND id IN (:ids) AND lastPlayedAt IS NULL")
    suspend fun deleteIfNeverPlayed(podcastId: Long, ids: List<String>)

    /**
     * Records playback activity - the one entry point for mutating playback state, written by
     * the player's progress writer. `lastPlayedAt` is always stamped to [now], since that's the
     * jump-target anchor - see [getLastListened] and [com.solewis.podcaster.domain.JumpTargetResolver].
     */
    @Query(
        """
        UPDATE episodes SET
            positionMillis = :positionMillis,
            isPlayed = :isPlayed,
            lastPlayedAt = :now,
            playedAt = CASE WHEN :isPlayed THEN :now ELSE playedAt END
        WHERE id = :id
        """
    )
    suspend fun setProgress(id: String, positionMillis: Long, isPlayed: Boolean, now: Long)

    /**
     * Backfills a real duration once the player has reported one - feeds are frequently missing
     * or wrong here (verified against real captured feeds). The `WHERE` clause is a no-op guard,
     * not just an optimization: the player reports this on every `onEvents` firing during
     * playback, and without it we'd re-write an unchanged value on every such callback.
     */
    @Query(
        """
        UPDATE episodes SET durationMillis = :durationMillis, durationIsExact = 1
        WHERE id = :id AND (durationIsExact = 0 OR durationMillis != :durationMillis)
        """
    )
    suspend fun backfillDuration(id: String, durationMillis: Long)

    /**
     * Every episode across every subscription, newest-published first, for the "All Episodes"
     * feed. Joined against `podcasts` because a cross-show list has to say which show each row
     * belongs to - the single-show list ([observeListForPodcast]) already knows that from the
     * screen it's on and has no use for the extra columns.
     *
     * Not paginated: this is a personal, single-user library, and the per-show list already
     * establishes that a plain in-memory list is fine at the scale one person's subscriptions
     * reach (a single show alone was tested at 2952 episodes with no issue). Revisit only if a
     * real library ever makes this noticeably slow.
     */
    @Query(
        """
        SELECT e.id, e.podcastId, p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl,
               e.title, e.pubDateMillis, e.durationMillis, e.displayNumber, e.episodeType,
               e.artworkUrl, e.positionMillis, e.isPlayed, e.lastPlayedAt
        FROM episodes e
        JOIN podcasts p ON p.id = e.podcastId
        ORDER BY (e.pubDateMillis IS NULL) ASC, e.pubDateMillis DESC
        """
    )
    fun observeAllEpisodes(): Flow<List<EpisodeFeedItem>>
}
