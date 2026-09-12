package com.solewis.podcaster.data.db.model

/**
 * The two columns the description-preview backfill needs, and nothing else - see
 * [com.solewis.podcaster.data.db.EpisodeDao.episodesMissingDescriptionPreview]. The full row
 * carries every other field of an episode alongside the description itself, which is the biggest
 * column in the table; the backfill reads the whole library in one go and has no use for any of it.
 */
data class EpisodeDescriptionSource(
    val id: String,
    val descriptionHtml: String
)
