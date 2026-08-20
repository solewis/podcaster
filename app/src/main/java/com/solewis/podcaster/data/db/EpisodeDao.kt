package com.solewis.podcaster.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.solewis.podcaster.data.db.entity.EpisodeEntity
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
     * The headline feature's data source: the episode this show was most recently played, if
     * any. Backed by the `(podcastId, lastPlayedAt)` index, so this is a single index range scan
     * rather than a table sweep even on a show with thousands of episodes.
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
}
