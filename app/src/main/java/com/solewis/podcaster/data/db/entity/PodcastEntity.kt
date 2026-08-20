package com.solewis.podcaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.solewis.podcaster.data.db.model.SortOrder

/**
 * A subscribed show. Per-show preferences (sort order, playback overrides) live as columns here
 * rather than in a separate table: there is exactly one row per show either way, so a separate
 * table would only add a join for no benefit.
 *
 * The primary key is a surrogate [id], not [feedUrl]: feed URLs change (host migrations,
 * `<itunes:new-feed-url>`, http-to-https), and a surrogate key turns that into a one-column
 * update instead of a cascading rewrite of every episode row.
 */
@Entity(
    tableName = "podcasts",
    indices = [Index(value = ["feedUrl"], unique = true)]
)
data class PodcastEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val feedUrl: String,
    /** iTunes `collectionId`, if this show was subscribed via search. Null for a raw feed-URL add. */
    val itunesCollectionId: Long? = null,

    val title: String,
    val author: String? = null,
    val description: String? = null,
    val artworkUrl: String? = null,
    val websiteUrl: String? = null,
    /** `<itunes:type>` - "episodic" | "serial", or null if the feed doesn't declare one. */
    val feedKind: String? = null,

    val subscribedAt: Long,

    // --- refresh bookkeeping, used for conditional GET and cold-start staleness checks ---
    val lastRefreshedAt: Long? = null,
    val lastRefreshFailedAt: Long? = null,
    val lastRefreshError: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val httpEtag: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val httpLastModified: String? = null,

    // --- per-show preferences ---
    @ColumnInfo(defaultValue = "'NEWEST_FIRST'")
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    val playbackSpeedOverride: Float? = null,
    val autoPlayNextOverride: Boolean? = null
)
