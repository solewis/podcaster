package com.solewis.podcaster.data.db.model

/** A subscription as shown in Home's horizontal strip - artwork-only browsing, so nothing
 * beyond identity/title (for the tap target's content description) and artwork is needed. */
data class HomeShowSummary(
    val id: Long,
    val title: String,
    val artworkUrl: String?,
    /** Not shown directly - present so the ordering query's column set matches this projection
     * exactly, with no unused-column warning from Room. */
    val lastListenedAt: Long?
)
