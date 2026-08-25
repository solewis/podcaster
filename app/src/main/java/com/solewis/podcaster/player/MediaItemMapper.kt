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
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .apply { episode.artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        return MediaItem.Builder()
            .setMediaId(episode.episodeId)
            .setUri(episode.mediaUrl)
            .setCustomCacheKey(episode.episodeId)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * A folder node for external browsers (Android Auto's media grid) - no URI, since it isn't
     * playable itself. [id] is opaque to the player but meaningful to
     * [com.solewis.podcaster.player.PodcastLibrarySessionCallback.onGetChildren], which parses it
     * back out to decide what to return as this node's children.
     */
    fun toBrowsableMediaItem(id: String, title: String, artworkUrl: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .apply { artworkUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }
}
