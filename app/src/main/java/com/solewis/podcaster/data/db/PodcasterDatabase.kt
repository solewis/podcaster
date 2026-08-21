package com.solewis.podcaster.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.solewis.podcaster.data.db.entity.EpisodeEntity
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.entity.QueueEntity

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class PodcasterDatabase : RoomDatabase() {
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun queueDao(): QueueDao

    companion object {
        const val NAME = "podcaster.db"
    }
}
