package com.solewis.podcaster.data.db.model

/** A queue row for display - joined against its episode and that episode's podcast. */
data class QueueItem(
    val queueId: Long,
    val episodeId: String,
    val title: String,
    val durationMillis: Long?,
    val artworkUrl: String?,
    val podcastTitle: String,
    val podcastArtworkUrl: String?
)
