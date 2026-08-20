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

    suspend fun backfillDuration(episodeId: String, durationMillis: Long) {
        episodeDao.backfillDuration(episodeId, durationMillis)
    }

    /**
     * [EpisodeListItem] (what the show list holds) deliberately omits `enclosureUrl` - it's a
     * lightweight list projection. This fetches the one field playback actually needs, packaged
     * with the podcast title the caller already has on hand (avoiding a second podcast lookup
     * here just to build an "artist" string).
     *
     * A finished episode always starts over from 0 rather than resuming - there is nothing left
     * to resume to, and a stale near-the-end position would otherwise flash briefly before the
     * next completion check corrected it.
     */
    suspend fun getPlayable(episodeId: String, podcastTitle: String): PlayableEpisode? {
        val entity = episodeDao.getById(episodeId) ?: return null
        return PlayableEpisode(
            episodeId = entity.id,
            title = entity.title,
            podcastTitle = podcastTitle,
            artworkUrl = entity.artworkUrl,
            mediaUrl = entity.enclosureUrl,
            startPositionMillis = if (entity.isPlayed) 0L else entity.positionMillis
        )
    }
}
