package com.solewis.podcaster.domain

/**
 * Assigns chronological order and display numbers to a feed's episodes.
 *
 * Verified against two real feeds before writing this (see the project plan): neither includes
 * `<itunes:episode>` on any item, so the numbers a user sees ("Ep 47") are never simply read out
 * of the feed - they are computed here, deterministically, so they stay stable across refreshes.
 */
object EpisodeNumbering {

    data class Input(
        val feedPosition: Int,
        val pubDateEpochMillis: Long?,
        val itunesEpisodeNumber: Int?,
        /** "full" | "trailer" | "bonus" */
        val episodeType: String
    )

    data class Result(
        val feedPosition: Int,
        /** 1-based chronological order among "full" episodes only; null for trailers/bonus. */
        val chronoIndex: Int?,
        /** What the UI shows as "Ep N". Null for trailers/bonus, which render as a type chip instead. */
        val displayNumber: Int?
    )

    fun assign(items: List<Input>): List<Result> {
        val fullItems = items.filter { it.episodeType == "full" }
        val nonFullItems = items.filter { it.episodeType != "full" }

        val chronoIndexByPosition = chronologicalSort(fullItems)
            .mapIndexed { index, item -> item.feedPosition to (index + 1) }
            .toMap()

        val useItunesNumbers = fullItems.isNotEmpty() &&
            fullItems.all { it.itunesEpisodeNumber != null } &&
            fullItems.map { it.itunesEpisodeNumber }.toSet().size == fullItems.size

        val fullResults = fullItems.map { item ->
            val chronoIndex = chronoIndexByPosition[item.feedPosition]
            val displayNumber = if (useItunesNumbers) item.itunesEpisodeNumber else chronoIndex
            Result(item.feedPosition, chronoIndex, displayNumber)
        }
        val nonFullResults = nonFullItems.map { item -> Result(item.feedPosition, null, null) }

        return fullResults + nonFullResults
    }

    /**
     * Oldest-first order. If every item has a pub date, sorts by it (ties broken by descending
     * feed position, since document order is conventionally newest-first). If ANY item is
     * missing a pub date, dates are ignored for the whole feed and feed position alone decides
     * order - mixing dated and undated ordering within one show would be worse than a
     * consistently positional fallback.
     */
    private fun chronologicalSort(items: List<Input>): List<Input> {
        val allDated = items.isNotEmpty() && items.all { it.pubDateEpochMillis != null }
        return if (allDated) {
            items.sortedWith(compareBy({ it.pubDateEpochMillis }, { -it.feedPosition }))
        } else {
            items.sortedByDescending { it.feedPosition }
        }
    }
}
