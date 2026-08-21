package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.PodcastDao
import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.HomeShowSummary
import com.solewis.podcaster.data.db.model.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Read access to subscriptions for the Home/Subscriptions screens - kept separate from
 * [SubscriptionRepository], which owns the subscribe/refresh write path. */
class PodcastRepository(private val podcastDao: PodcastDao) {
    fun observeAll(): Flow<List<PodcastEntity>> = podcastDao.observeAll()
    fun observeById(id: Long): Flow<PodcastEntity?> = podcastDao.observeById(id)
    fun observeHomeOrder(): Flow<List<HomeShowSummary>> = podcastDao.observeHomeOrder()

    /** feedUrl -> podcastId, for screens (like Search) that only know a show by its feed URL. */
    fun observeSubscribedFeedUrls(): Flow<Map<String, Long>> =
        podcastDao.observeAll().map { podcasts -> podcasts.associate { it.feedUrl to it.id } }

    suspend fun setSortOrder(podcastId: Long, sortOrder: SortOrder) {
        podcastDao.setSortOrder(podcastId, sortOrder)
    }

    /** Removes the show and, via a cascading foreign key, every one of its episodes and their
     * playback progress - there is no undo. */
    suspend fun unsubscribe(podcastId: Long) {
        podcastDao.delete(podcastId)
    }
}
