package com.solewis.podcaster.domain

import java.net.URI
import java.security.MessageDigest

/**
 * Resolves a stable identity for an RSS item so playback progress survives a feed refresh.
 *
 * Real feeds are unreliable here in three different ways, verified against real captured feeds
 * before writing this (see the project plan): some feeds reuse a single static `<guid>` for
 * every item; CDNs commonly wrap the enclosure URL in a tracking redirector and/or rotate a
 * query-string token on every fetch, so the same episode's URL is never byte-identical twice; and
 * a minority of feeds have neither a usable guid nor a stable URL. This picks the first workable
 * source, in order of trustworthiness, and records which source was used so a pathological feed
 * is diagnosable later.
 */
object EpisodeIdentity {

    enum class Source { GUID, ENCLOSURE, HASH }

    data class Key(val value: String, val source: Source)

    // Known wrapper conventions where the real target is embedded in the path right after a
    // fixed prefix. Not exhaustive - the dominant real-world failure this defends against (a
    // rotating ?token=/&updated= query parameter on an otherwise-stable URL) is already solved
    // by dropping the query string below, regardless of whether a wrapper gets fully unwound.
    private val TRACKING_PREFIXES = listOf(
        "dts.podtrac.com/redirect.mp3/",
        "pdst.fm/e/",
        "chtbl.com/track/"
    )

    /**
     * @param guidIsUniqueInFeed whether [guid] is used by exactly one item in the feed being
     * processed - callers must compute this across the whole feed first (see [findDuplicateGuids]),
     * since a guid that looks fine in isolation but is reused by every item in a broken feed must
     * not be trusted for any of them.
     * @param title, pubDateEpochMillis, enclosureBytes used only for the last-resort hash.
     */
    fun resolve(
        guid: String?,
        guidIsUniqueInFeed: Boolean,
        enclosureUrl: String?,
        title: String?,
        pubDateEpochMillis: Long?,
        enclosureBytes: Long?
    ): Key {
        val trimmedGuid = guid?.trim()
        if (!trimmedGuid.isNullOrEmpty() && guidIsUniqueInFeed) {
            return Key(trimmedGuid, Source.GUID)
        }

        val normalizedEnclosure = enclosureUrl?.let(::normalizeEnclosureUrl)
        if (!normalizedEnclosure.isNullOrEmpty()) {
            return Key(normalizedEnclosure, Source.ENCLOSURE)
        }

        val hashInput = "${title.orEmpty()}|${pubDateEpochMillis ?: 0}|${enclosureBytes ?: 0}"
        return Key(sha256Hex(hashInput), Source.HASH)
    }

    /** @return the guid strings (trimmed, non-blank) that appear more than once among [guids]. */
    fun findDuplicateGuids(guids: List<String?>): Set<String> {
        val trimmed = guids.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return trimmed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }

    /**
     * The Room primary key for an episode: stable across refreshes, and directly usable as both
     * `MediaItem.mediaId` and the ExoPlayer `customCacheKey` with no lookup table anywhere else.
     */
    fun primaryKey(podcastId: Long, key: Key): String = primaryKey(podcastId, key.value)

    /** As above, for callers that already have a flattened stable-key string (e.g. from a Room row). */
    fun primaryKey(podcastId: Long, stableKey: String): String {
        val digest = sha256Hex(stableKey).take(16)
        return "$podcastId:$digest"
    }

    internal fun normalizeEnclosureUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null

        val uri = runCatching { URI(trimmed) }.getOrNull()
        var combined = if (uri?.host != null) {
            val host = uri.host.lowercase()
            val port = if (uri.port != -1) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            "$host$port$path"
        } else {
            // Not a well-formed absolute URI - fall back to manual query/fragment stripping so
            // we still produce a stable key rather than giving up entirely.
            trimmed.substringBefore('?').substringBefore('#')
        }

        var strippedSomething: Boolean
        do {
            strippedSomething = false
            for (prefix in TRACKING_PREFIXES) {
                if (combined.startsWith(prefix, ignoreCase = true)) {
                    combined = combined.substring(prefix.length)
                    strippedSomething = true
                }
            }
        } while (strippedSomething)

        return combined.trimEnd('/').takeIf { it.isNotEmpty() }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
