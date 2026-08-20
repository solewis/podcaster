package com.solewis.podcaster.domain

import com.solewis.podcaster.data.db.model.EpisodeListItem

/**
 * The headline feature's rule: given a show's full episode list, where should "Jump to last
 * listened" take you?
 *
 * Deliberately a pure function over the already-loaded episode list rather than a separate
 * database query: the show screen already holds every episode in memory to render the list (that
 * is unavoidable - it needs to be scrollable), so answering this from that same list is both
 * simpler than a second query and, more importantly, guarantees the target is always consistent
 * with what's actually on screen. An O(n) scan over a show's episodes is microseconds even at
 * thousands of items.
 *
 * Deliberately never looks at sort order: the target episode is the same regardless of whether
 * the show is displayed newest-first or oldest-first. Only the *scroll index* to reach it
 * changes, and that's computed separately once the sorted, flattened list exists (see
 * `ShowViewModel`).
 */
object JumpTargetResolver {

    enum class Intent { RESUME, NEXT, REVISIT }

    data class Target(
        val episodeId: String,
        val intent: Intent
    )

    /**
     * The rule, in order:
     * 1. `M` = the episode with the greatest `lastPlayedAt`. None -> no target at all.
     * 2. `M` not finished -> target is `M` itself, [Intent.RESUME].
     * 3. `M` finished and there's an unplayed full episode after it (by [EpisodeListItem.chronoIndex],
     *    chronological order - not sort order) -> target is that episode, [Intent.NEXT].
     * 4. `M` finished and nothing after it (caught up) -> target is `M` again, [Intent.REVISIT].
     *
     * Recency of *listening* is the only signal that survives out-of-order play, which is why
     * this beats "most recently published" - see the project plan for the full reasoning.
     */
    fun resolve(episodes: List<EpisodeListItem>): Target? {
        val lastListened = episodes
            .filter { it.lastPlayedAt != null }
            .maxByOrNull { it.lastPlayedAt!! }
            ?: return null

        if (!lastListened.isPlayed) {
            return Target(lastListened.id, Intent.RESUME)
        }

        val nextUnplayed = lastListened.chronoIndex?.let { anchorIndex ->
            episodes
                .asSequence()
                .filter { it.episodeType == "full" && !it.isPlayed }
                .filter { (it.chronoIndex ?: Int.MIN_VALUE) > anchorIndex }
                .minByOrNull { it.chronoIndex!! }
        }

        return if (nextUnplayed != null) {
            Target(nextUnplayed.id, Intent.NEXT)
        } else {
            Target(lastListened.id, Intent.REVISIT)
        }
    }
}
