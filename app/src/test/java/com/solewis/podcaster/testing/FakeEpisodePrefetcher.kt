package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.player.EpisodePrefetcher

/** Records what it was asked to prefetch, in order. */
class FakeEpisodePrefetcher : EpisodePrefetcher {

    val requested = mutableListOf<String>()

    override fun prefetch(episode: PlayableEpisode) {
        requested += episode.episodeId
    }
}
