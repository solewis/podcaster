package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.EpisodeDao
import com.solewis.podcaster.data.db.model.EpisodeListItem
import kotlinx.coroutines.flow.Flow

class EpisodeRepository(private val episodeDao: EpisodeDao) {
    fun observeEpisodes(podcastId: Long): Flow<List<EpisodeListItem>> =
        episodeDao.observeListForPodcast(podcastId)
}
