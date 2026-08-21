package com.solewis.podcaster.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.solewis.podcaster.data.db.entity.QueueEntity
import com.solewis.podcaster.data.db.model.QueueItem
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {

    @Query(
        """
        SELECT q.id AS queueId, e.id AS episodeId, e.title, e.durationMillis, e.artworkUrl,
               p.title AS podcastTitle, p.artworkUrl AS podcastArtworkUrl
        FROM queue q
        JOIN episodes e ON e.id = q.episodeId
        JOIN podcasts p ON p.id = e.podcastId
        ORDER BY q.position ASC
        """
    )
    fun observeQueue(): Flow<List<QueueItem>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM queue")
    suspend fun nextPosition(): Int

    /** Ignored on conflict rather than replacing - re-queuing an episode already in the queue is
     * a no-op, not a duplicate row (see the unique index on [QueueEntity.episodeId]). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: QueueEntity)

    @Query("DELETE FROM queue WHERE id = :queueId")
    suspend fun deleteById(queueId: Long)

    @Query("SELECT * FROM queue ORDER BY position ASC LIMIT 1")
    suspend fun peekFront(): QueueEntity?

    @Query("SELECT * FROM queue ORDER BY position ASC")
    suspend fun getAllOrdered(): List<QueueEntity>

    @Query("UPDATE queue SET position = :position WHERE id = :queueId")
    suspend fun setPosition(queueId: Long, position: Int)
}
