package com.solewis.podcaster.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single episode, with playback progress on the SAME row as its feed metadata.
 *
 * This is deliberate, not an oversight: progress is strictly 1:1 with an episode and every list
 * query needs it, so a separate `playback_state` table would only add a `LEFT JOIN` to the hot
 * path of a list with hundreds or thousands of rows, for no benefit.
 *
 * The consequence: **feed refresh must never `@Upsert`/`REPLACE` a row** - that would silently
 * reset every resume position to zero, destroying the entire reason this app exists. Refresh must
 * use [com.solewis.podcaster.data.db.EpisodeDao.insertNew] for genuinely new episodes plus
 * [com.solewis.podcaster.data.db.EpisodeDao.updateMetadata] for existing ones, which touches no
 * playback column. See `EpisodeDaoTest.refresh_does_not_clobber_progress`.
 */
@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(
            entity = PodcastEntity::class,
            parentColumns = ["id"],
            childColumns = ["podcastId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["podcastId", "chronoIndex"]),
        // Backs the "what is the last-listened episode for this show" query - the headline
        // feature's jump target - as a single index range scan rather than a table sweep.
        Index(value = ["podcastId", "lastPlayedAt"]),
        Index(value = ["podcastId", "stableKey"], unique = true),
        Index(value = ["lastPlayedAt"])
    ]
)
data class EpisodeEntity(
    /** Deterministic: "$podcastId:${sha256(stableKey).take(16)}" - see EpisodeIdentity.primaryKey. */
    @PrimaryKey
    val id: String,
    val podcastId: Long,

    /** The raw identity source value (guid, normalized enclosure URL, or a content hash). */
    val stableKey: String,
    /** "guid" | "enclosure" | "hash" - kept for diagnosing a pathological feed. */
    val stableKeySource: String,

    // --- feed metadata ---
    val title: String,
    val descriptionHtml: String? = null,
    val pubDateMillis: Long? = null,
    val enclosureUrl: String,
    val enclosureBytes: Long? = null,
    val enclosureMimeType: String? = null,
    val durationMillis: Long? = null,
    /** False until ExoPlayer reports a real duration - feeds frequently lie about this. */
    @ColumnInfo(defaultValue = "0")
    val durationIsExact: Boolean = false,
    val artworkUrl: String? = null,
    val itunesEpisodeNumber: Int? = null,
    val itunesSeason: Int? = null,
    /** "full" | "trailer" | "bonus" */
    @ColumnInfo(defaultValue = "'full'")
    val episodeType: String = "full",
    val webPageUrl: String? = null,

    // --- ordering, recomputed wholesale on every refresh (see EpisodeNumbering) ---
    val feedPosition: Int,
    /** 1 = oldest full episode. Null for trailers/bonus. */
    val chronoIndex: Int? = null,
    /** What the UI shows as "Ep N". Null for trailers/bonus, which show a type chip instead. */
    val displayNumber: Int? = null,

    // --- playback state - see the class doc above before touching any of this in a refresh ---
    @ColumnInfo(defaultValue = "0")
    val positionMillis: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val isPlayed: Boolean = false,
    /** Set on ANY playback activity, including completion - this is the jump-target anchor. */
    val lastPlayedAt: Long? = null,
    val playedAt: Long? = null,

    val firstSeenAt: Long
)
