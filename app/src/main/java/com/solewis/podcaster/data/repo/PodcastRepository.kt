package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.PodcastDao
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.SortOrder
import kotlinx.coroutines.flow.Flow

/** Read access to subscriptions for the Library screen - kept separate from
 * [SubscriptionRepository], which owns the subscribe/refresh write path. */
class PodcastRepository(private val podcastDao: PodcastDao) {
    fun observeAll(): Flow<List<PodcastEntity>> = podcastDao.observeAll()
    fun observeById(id: Long): Flow<PodcastEntity?> = podcastDao.observeById(id)

    suspend fun setSortOrder(podcastId: Long, sortOrder: SortOrder) {
        podcastDao.setSortOrder(podcastId, sortOrder)
    }
}
