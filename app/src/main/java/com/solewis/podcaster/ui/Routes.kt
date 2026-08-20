package com.solewis.podcaster.ui

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Library : Route

    @Serializable
    data object AllEpisodes : Route

    @Serializable
    data object Search : Route

    @Serializable
    data class Show(val podcastId: Long) : Route

    @Serializable
    data class ShowPreview(
        val feedUrl: String,
        val itunesCollectionId: Long?,
        val title: String,
        val author: String?,
        val artworkUrl: String?
    ) : Route

    @Serializable
    data object NowPlaying : Route
}
