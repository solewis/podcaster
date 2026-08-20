package com.solewis.podcaster.domain

import com.solewis.podcaster.data.remote.ParsedItem

/**
 * Combines [EpisodeIdentity], [EpisodeNumbering], [FeedDateParser], and [DurationParser] into
 * one pass over a parsed feed's items. Kept as pure Kotlin (no Room/Android dependency) so it's
 * unit-testable directly against the real captured feeds; the repository layer is responsible
 * for turning [MappedEpisode] into a Room entity (adding the podcast id, the primary key, and
 * initial playback-state defaults).
 */
object FeedToEpisodesMapper {

    data class MappedEpisode(
        val stableKey: String,
        val stableKeySource: String,
        val title: String,
        val descriptionHtml: String?,
        val pubDateMillis: Long?,
        val enclosureUrl: String,
        val enclosureBytes: Long?,
        val enclosureMimeType: String?,
        val durationMillis: Long?,
        val artworkUrl: String?,
        val itunesEpisodeNumber: Int?,
        val itunesSeason: Int?,
        val episodeType: String,
        val webPageUrl: String?,
        val feedPosition: Int,
        val chronoIndex: Int?,
        val displayNumber: Int?
    )

    /** Items with no enclosure URL are dropped - there is nothing to play, so nothing to store. */
    fun map(items: List<ParsedItem>): List<MappedEpisode> {
        val playable = items.filter { !it.enclosureUrl.isNullOrBlank() }

        val duplicateGuids = EpisodeIdentity.findDuplicateGuids(playable.map { it.guid })
        val pubDateByPosition = playable.associate { it.feedPosition to FeedDateParser.parseToEpochMillis(it.pubDateRaw) }

        val numberingByPosition = EpisodeNumbering.assign(
            playable.map { item ->
                EpisodeNumbering.Input(
                    feedPosition = item.feedPosition,
                    pubDateEpochMillis = pubDateByPosition[item.feedPosition],
                    itunesEpisodeNumber = item.itunesEpisodeNumber,
                    episodeType = item.episodeType
                )
            }
        ).associateBy { it.feedPosition }

        return playable.map { item ->
            val trimmedGuid = item.guid?.trim()
            val guidIsUnique = !trimmedGuid.isNullOrEmpty() && trimmedGuid !in duplicateGuids
            val pubDateMillis = pubDateByPosition[item.feedPosition]

            val key = EpisodeIdentity.resolve(
                guid = item.guid,
                guidIsUniqueInFeed = guidIsUnique,
                enclosureUrl = item.enclosureUrl,
                title = item.title,
                pubDateEpochMillis = pubDateMillis,
                enclosureBytes = item.enclosureBytes
            )
            val numbering = numberingByPosition[item.feedPosition]

            MappedEpisode(
                stableKey = key.value,
                stableKeySource = key.source.name.lowercase(),
                title = item.title?.takeIf(String::isNotBlank) ?: "(untitled episode)",
                descriptionHtml = item.descriptionHtml,
                pubDateMillis = pubDateMillis,
                enclosureUrl = requireNotNull(item.enclosureUrl),
                enclosureBytes = item.enclosureBytes,
                enclosureMimeType = item.enclosureMimeType,
                durationMillis = DurationParser.parseToMillis(item.durationRaw),
                artworkUrl = item.imageUrl,
                itunesEpisodeNumber = item.itunesEpisodeNumber,
                itunesSeason = item.itunesSeason,
                episodeType = item.episodeType,
                webPageUrl = item.webPageUrl,
                feedPosition = item.feedPosition,
                chronoIndex = numbering?.chronoIndex,
                displayNumber = numbering?.displayNumber
            )
        }
    }
}
