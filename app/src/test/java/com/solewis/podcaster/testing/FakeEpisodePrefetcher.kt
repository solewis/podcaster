package com.solewis.podcaster.testing

import com.solewis.podcaster.data.repo.PlayableEpisode
import com.solewis.podcaster.player.EpisodePrefetcher
import java.util.concurrent.CopyOnWriteArrayList

/** Records what it was asked to prefetch, in order. */
class FakeEpisodePrefetcher : EpisodePrefetcher {

    // Copy-on-write: written from the code under test, read from polling assertions, on different
    // threads. See FakeDownloads for the ConcurrentModificationException this prevents.
    val requested: MutableList<String> = CopyOnWriteArrayList()

    override fun prefetch(episode: PlayableEpisode) {
        requested += episode.episodeId
    }
}
