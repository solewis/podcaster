package com.solewis.podcaster.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.data.db.model.SortOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface PodcastDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(podcast: PodcastEntity): Long

    @Query("SELECT * FROM podcasts WHERE feedUrl = :feedUrl LIMIT 1")
    suspend fun findByFeedUrl(feedUrl: String): PodcastEntity?

    @Query("SELECT * FROM podcasts ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<PodcastEntity>>

    /**
     * Most-recently-listened show first, falling back to most-recently-subscribed for shows
     * with no listening history yet - what Home's horizontal strip is ordered by. The subquery
     * is a single indexed lookup per show (backed by the same `(podcastId, lastPlayedAt)` index
     * [com.solewis.podcaster.data.db.EpisodeDao.getLastListened] uses), not a join fan-out, so
     * this stays cheap regardless of episode count.
     */
    @Query(
        """
        SELECT p.id AS id, p.title AS title, p.artworkUrl AS artworkUrl,
               (SELECT MAX(e.lastPlayedAt) FROM episodes e WHERE e.podcastId = p.id) AS lastListenedAt
        FROM podcasts p
        ORDER BY (lastListenedAt IS NULL) ASC, lastListenedAt DESC, p.subscribedAt DESC
        """
    )
    fun observeHomeOrder(): Flow<List<HomeShowSummary>>

    @Query("SELECT id FROM podcasts")
    suspend fun getAllIds(): List<Long>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    fun observeById(id: Long): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    suspend fun getById(id: Long): PodcastEntity?

    @Query(
        """
        UPDATE podcasts SET
            httpEtag = :etag,
            httpLastModified = :lastModified,
            lastRefreshedAt = :refreshedAt,
            lastRefreshFailedAt = NULL,
            lastRefreshError = NULL
        WHERE id = :podcastId
        """
    )
    suspend fun recordRefreshSuccess(podcastId: Long, etag: String?, lastModified: String?, refreshedAt: Long)

    @Query(
        """
        UPDATE podcasts SET lastRefreshFailedAt = :failedAt, lastRefreshError = :error
        WHERE id = :podcastId
        """
    )
    suspend fun recordRefreshFailure(podcastId: Long, failedAt: Long, error: String?)

    @Query("UPDATE podcasts SET sortOrder = :sortOrder WHERE id = :podcastId")
    suspend fun setSortOrder(podcastId: Long, sortOrder: SortOrder)

    @Query("DELETE FROM podcasts WHERE id = :podcastId")
    suspend fun delete(podcastId: Long)
}
