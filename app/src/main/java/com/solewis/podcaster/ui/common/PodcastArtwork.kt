package com.solewis.podcaster.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * Show/episode artwork, everywhere it appears - one shared composable rather than each screen
 * repeating `AsyncImage` + its own clip/shape. A tonal music-note glyph sits underneath and shows
 * through whenever there's no URL, the image is still loading, or the request fails (a fair few
 * real feeds have artwork hosts that reject requests or omit artwork entirely) - so there's never
 * a blank gap where a rounded rectangle full of nothing would otherwise sit.
 *
 * Rounded from the bottom of the shared shape scale by default. A radius is only ever read relative
 * to what it is rounding, and the scale's `medium` took most of the corner off a 48dp thumbnail,
 * which read as a squircle rather than as a picture of something. Screens showing artwork much
 * larger than a list thumbnail pass a shape one or two steps up to keep the proportion similar.
 */
@Composable
fun PodcastArtwork(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraSmall
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxSize(0.4f)
        )
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Thumbnail size for an episode inside a list. Shared so the Home feed and a show's own episode
 * list stay identical - the same episode should not be a different size depending on which list you
 * happened to reach it from.
 *
 * Trimmed from 56dp once list rows grew a description preview and a row of controls: at 56 the
 * artwork was competing with the text for the row rather than labelling it, and the row was tall
 * enough already.
 */
val EpisodeArtworkSize = 48.dp

