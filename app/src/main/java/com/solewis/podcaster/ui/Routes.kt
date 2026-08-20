package com.solewis.podcaster.ui

import kotlinx.serialization.Serializable

sealed interface Route {
    @Serializable
    data object Library : Route

    @Serializable
    data object Search : Route

    @Serializable
    data class Show(val podcastId: Long) : Route

    @Serializable
    data object NowPlaying : Route
}
