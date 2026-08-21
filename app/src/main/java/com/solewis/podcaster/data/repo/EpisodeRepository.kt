package com.solewis.podcaster.data.repo

import com.solewis.podcaster.data.db.EpisodeDao
import com.solewis.podcaster.data.db.PodcastDao
import com.solewis.podcaster.data.db.model.EpisodeFeedItem
import com.solewis.podcaster.data.db.model.EpisodeListItem
import com.solewis.podcaster.domain.HtmlToText
import kotlinx.coroutines.flow.Flow

class EpisodeRepository(private val episodeDao: EpisodeDao, private val podcastDao: PodcastDao) {
    fun observeEpisodes(podcastId: Long): Flow<List<EpisodeListItem>> =
        episodeDao.observeListForPodcast(podcastId)

    fun observeAllEpisodes(): Flow<List<EpisodeFeedItem>> = episodeDao.observeAllEpisodes()

    /** Records playback activity for an episode - written by the player's progress writer. */
    suspend fun setProgress(episodeId: String, positionMillis: Long, isPlayed: Boolean) {
        episodeDao.setProgress(episodeId, positionMillis, isPlayed, System.currentTimeMillis())
    }

    suspend fun backfillDuration(episodeId: String, durationMillis: Long) {
        episodeDao.backfillDuration(episodeId, durationMillis)
    }

    /**
     * The full show notes, converted to plain text - loaded on demand, by id, only when an
     * episode's detail sheet is opened. [EpisodeListItem]/[EpisodeFeedItem] deliberately omit
     * this: it can be several KB of HTML per row, which a list of hundreds of episodes has no
     * business holding in memory just to render titles and durations.
     */
    suspend fun getDescription(episodeId: String): String? =
        HtmlToText.toPlainText(episodeDao.getById(episodeId)?.descriptionHtml)

    /**
     * [EpisodeListItem]/[EpisodeFeedItem] (what the list screens hold) deliberately omit
     * `enclosureUrl` - they're lightweight list projections. This fetches the one field playback
     * actually needs, packaged with the podcast title/artwork the caller already has on hand
     * (avoiding a second podcast lookup here just to build an "artist" string).
     *
     * [podcastArtworkUrl] is used only when the episode has no artwork of its own - common in
     * real feeds, verified while building the RSS parser - so the notification/Now Playing
     * screen still shows something rather than a blank image.
     *
     * A finished episode always starts over from 0 rather than resuming - there is nothing left
     * to resume to, and a stale near-the-end position would otherwise flash briefly before the
     * next completion check corrected it.
     */
    suspend fun getPlayable(
        episodeId: String,
        podcastTitle: String,
        podcastArtworkUrl: String? = null
    ): PlayableEpisode? {
        val entity = episodeDao.getById(episodeId) ?: return null
        return PlayableEpisode(
            episodeId = entity.id,
            title = entity.title,
            podcastTitle = podcastTitle,
            artworkUrl = entity.artworkUrl ?: podcastArtworkUrl,
            mediaUrl = entity.enclosureUrl,
            startPositionMillis = if (entity.isPlayed) 0L else entity.positionMillis
        )
    }

    /**
     * Same as [getPlayable], but for callers (the personal queue, auto-advance) that only have
     * an episode id on hand and no podcast context already loaded - resolves the podcast title
     * and artwork itself via one extra lookup.
     */
    suspend fun getPlayableById(episodeId: String): PlayableEpisode? {
        val entity = episodeDao.getById(episodeId) ?: return null
        val podcast = podcastDao.getById(entity.podcastId) ?: return null
        return PlayableEpisode(
            episodeId = entity.id,
            title = entity.title,
            podcastTitle = podcast.title,
            artworkUrl = entity.artworkUrl ?: podcast.artworkUrl,
            mediaUrl = entity.enclosureUrl,
            startPositionMillis = if (entity.isPlayed) 0L else entity.positionMillis
        )
    }

    /** The next unplayed episode in [currentEpisodeId]'s own show - what auto-advance and the
     * manual skip button fall back to once the personal queue is empty. Null for a trailer/bonus
     * episode (no [com.solewis.podcaster.data.db.entity.EpisodeEntity.chronoIndex]) or when
     * there's nothing left unplayed after it. */
    suspend fun getNextInShow(currentEpisodeId: String): PlayableEpisode? {
        val current = episodeDao.getById(currentEpisodeId) ?: return null
        val chronoIndex = current.chronoIndex ?: return null
        val next = episodeDao.findNextUnplayed(current.podcastId, chronoIndex) ?: return null
        return getPlayableById(next.id)
    }
}
