package com.solewis.podcaster.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    shape: Shape = MaterialTheme.shapes.extraSmall,
    /**
     * Marks this as the episode loaded in the player, for a list row - see [RowPlaybackState].
     *
     * Here rather than further down the row because this is the only thing in a row with enough
     * visual mass to be caught from a moving list: the bars first sat at the start of the metadata
     * line, which is the fourth line down, and were reported as very hard to see while scrolling.
     * Artwork is top-left, where scanning a list starts, and roughly five times the size.
     *
     * Bars rather than a play or pause triangle on purpose. Those are *action* icons, and the row's
     * own play button already carries one - it shows a pause icon while playing, so a play triangle
     * on the artwork beside it would put two opposite icons in the same row. The artwork is not
     * tappable anyway (the row opens the episode), so an action icon here would be a lie.
     */
    playbackState: RowPlaybackState = RowPlaybackState.Inactive
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        // Dropped once the image is actually up. The glyph used to stay composed, measured and
        // drawn underneath every loaded thumbnail forever, where nothing could ever see it - one
        // wasted vector draw per row, on a list where rows are the thing being scrolled.
        var showGlyph by remember(artworkUrl) { mutableStateOf(true) }

        // Suppressed while this row is marked, not only once an image loads. The marker draws its
        // own bars in the middle of the same square, and stacking the two put a music note and an
        // equaliser on top of each other behind a scrim - which is not a subtle effect, and shows
        // up on every row whose artwork has not arrived yet, so on a fast scroll through a feed.
        // The scrim and bars already say "no picture, and this is the one playing".
        if (showGlyph && playbackState == RowPlaybackState.Inactive) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxSize(0.4f)
            )
        }
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Only success hides it. An error or an empty URL has to leave the glyph showing -
            // that is the whole reason it is here, rather than a blank rounded rectangle.
            onSuccess = { showGlyph = false },
            onError = { showGlyph = true },
            modifier = Modifier.fillMaxSize()
        )

        if (playbackState != RowPlaybackState.Inactive) {
            // A dark scrim rather than a tint from the palette: this sits on artwork the app does
            // not choose and cannot predict, and black at half strength is the one thing that
            // holds a white glyph legible over a pale cover and a dark one alike. Inside the Box,
            // so it picks up the same clip and never squares off the corners.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ScrimColor),
                contentAlignment = Alignment.Center
            ) {
                NowPlayingEqualizer(
                    isPlaying = playbackState == RowPlaybackState.Playing,
                    color = Color.White,
                    size = OverlayGlyphSize
                )
            }
        }
    }
}

private val ScrimColor = Color.Black.copy(alpha = 0.5f)

/** Generous against a 48dp thumbnail, because being seen at a glance is the entire point. */
private val OverlayGlyphSize = 22.dp

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

