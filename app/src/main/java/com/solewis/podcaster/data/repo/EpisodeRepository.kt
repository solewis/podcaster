package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.EpisodeDao
import com.solewis.podcaster.data.db.model.EpisodeListItem
import kotlinx.coroutines.flow.Flow

class EpisodeRepository(private val episodeDao: EpisodeDao) {
    fun observeEpisodes(podcastId: Long): Flow<List<EpisodeListItem>> =
        episodeDao.observeListForPodcast(podcastId)

    /**
     * Records playback activity for an episode. Until Phase 4+ this is called only from the
     * Show screen's debug-only seeding controls (see `ShowViewModel.debugSetProgress`), since no
     * player exists yet - but it's the same call the real player will make later, so nothing
     * about this path changes when that lands.
     */
    suspend fun setProgress(episodeId: String, positionMillis: Long, isPlayed: Boolean) {
        episodeDao.setProgress(episodeId, positionMillis, isPlayed, System.currentTimeMillis())
    }

    /**
     * [EpisodeListItem] (what the show list holds) deliberately omits `enclosureUrl` - it's a
     * lightweight list projection. This fetches the one field playback actually needs, packaged
     * with the podcast title the caller already has on hand (avoiding a second podcast lookup
     * here just to build an "artist" string).
     */
    suspend fun getPlayable(episodeId: String, podcastTitle: String): PlayableEpisode? {
        val entity = episodeDao.getById(episodeId) ?: return null
        return PlayableEpisode(
            episodeId = entity.id,
            title = entity.title,
            podcastTitle = podcastTitle,
            artworkUrl = entity.artworkUrl,
            mediaUrl = entity.enclosureUrl
        )
    }
}
