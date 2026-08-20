package com.solewis.podcaster.ui.show

import com.solewis.podcaster.data.db.entity.PodcastEntity
import com.solewis.podcaster.data.db.model.EpisodeListItem

/**
 * The flat list `ShowScreen`'s `LazyColumn` actually renders - a show header followed by every
 * episode row. Keeping the header as a real list item (rather than a separate composable above
 * the list) is what makes the jump target's scroll index a plain `indexOfFirst` with no
 * header-offset arithmetic to get wrong.
 */
sealed interface ShowListItem {
    val key: Any

    data class Header(val podcast: PodcastEntity, val episodeCount: Int) : ShowListItem {
        override val key: Any = "header"
    }

    data class Episode(val item: EpisodeListItem) : ShowListItem {
        override val key: Any = item.id
    }
}
