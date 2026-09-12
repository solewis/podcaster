package com.solewis.podcaster.ui.show

import com.solewis.podcaster.data.db.model.EpisodeListItem

/**
 * Which of a show's episodes the list is currently showing.
 *
 * Three options rather than a set of independent toggles: the two that are not [ALL] answer
 * different questions ("what have I still to hear" and "what can I play with no signal") and
 * combining them would mostly produce an empty list, since an episode is usually downloaded
 * *because* it is unfinished.
 */
enum class EpisodeFilter(val label: String) {
    ALL("All episodes"),
    UNFINISHED("Not finished"),
    DOWNLOADED("Downloaded");

    fun accepts(episode: EpisodeListItem, downloadedIds: Set<String>): Boolean = when (this) {
        ALL -> true
        UNFINISHED -> !episode.isPlayed
        DOWNLOADED -> episode.id in downloadedIds
    }
}
