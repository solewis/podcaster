package com.solewis.podcaster.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A personal "play next" list, independent of any one show - the episode itself carries its
 * podcast via [EpisodeEntity.podcastId], so a queue row only needs to point at the episode.
 *
 * [position] is a separate manual ordering rather than [addedAt] insertion order, since the
 * point of a queue is that you can reorder it. `onDelete = CASCADE` means unsubscribing a show
 * (which cascades to its episodes) also removes any of its episodes still sitting in the queue.
 */
@Entity(
    tableName = "queue",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episodeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        // An episode can only be queued once - re-adding it is a no-op (see QueueDao.insert).
        Index(value = ["episodeId"], unique = true),
        Index(value = ["position"])
    ]
)
data class QueueEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val episodeId: String,
    val position: Int,
    val addedAt: Long
)
