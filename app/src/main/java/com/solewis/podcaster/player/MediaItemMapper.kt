package com.solewis.podcaster.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.solewis.podcaster.data.repo.PlayableEpisode

object MediaItemMapper {

    /**
     * `mediaId` and `customCacheKey` are both set to the episode's id - the same id used as the
     * Room primary key. That makes the cache key a content identity rather than a URL, which is
     * what will let a future download feature and the streaming cache share one cache without
     * the CDN's tracking-token query strings defeating either.
     */
    fun toMediaItem(episode: PlayableEpisode): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.title)
            .setArtist(episode.podcastTitle)
            .apply { episode.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        return MediaItem.Builder()
            .setMediaId(episode.episodeId)
            .setUri(episode.mediaUrl)
            .setCustomCacheKey(episode.episodeId)
            .setMediaMetadata(metadata)
            .build()
    }
}
