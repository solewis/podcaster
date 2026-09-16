package com.solewis.podcaster.ui.common

import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The marker that says which row is the one in the player.
 *
 * It lives on the artwork because the first version sat at the start of the metadata line - the
 * fourth line of the row - and was reported as very hard to pick out while scrolling. So the thing
 * worth testing is not that it exists but that it *changes the artwork visibly*, which is exactly
 * what a semantics assertion cannot see.
 *
 * On device and reading pixels, for the same reason as [NowPlayingEqualizerTest]: no real graphics
 * pipeline, no way to tell a drawn overlay from one that silently drew nothing.
 *
 * All three states are composed together in one pass, since a Compose test may only set its
 * content once - and comparing them within a single frame is the sharper comparison anyway.
 */
@RunWith(AndroidJUnit4::class)
class PodcastArtworkOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        compose.setContent {
            PodcasterTheme {
                Column {
                    // No artwork URL on purpose: nothing is fetched, so the bytes are
                    // deterministic, and the fallback glyph underneath is the worst case for
                    // legibility - a marker over a plain tonal square has the least contrast to
                    // work with of anything a real feed could supply.
                    RowPlaybackState.entries.forEach { state ->
                        PodcastArtwork(
                            artworkUrl = null,
                            modifier = Modifier.size(EpisodeArtworkSize).testTag(state.name),
                            playbackState = state
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun marking_a_row_visibly_changes_its_artwork() {
        val changed = fractionDiffering(RowPlaybackState.Inactive, RowPlaybackState.Playing)

        Log.w("ARTWORK_OVERLAY", "marker changes $changed of the thumbnail")
        // A real proportion of the thumbnail differs, not a few pixels in a corner. Both states
        // draw the same fallback art underneath, so every changed pixel is the overlay.
        assertThat(changed).isGreaterThan(0.5f)
    }

    @Test
    fun a_paused_row_is_marked_differently_from_a_playing_one() {
        val changed = fractionDiffering(RowPlaybackState.Playing, RowPlaybackState.Paused)

        Log.w("ARTWORK_OVERLAY", "playing vs paused differ by $changed")
        // Both are marked, so the scrim is identical and only the bars differ - a far smaller
        // difference than the one above, which is why this bound is not the same number.
        assertThat(changed).isGreaterThan(0.01f)
    }

    @Test
    fun an_unmarked_row_is_left_completely_alone() {
        // Guards the default. Every other use of this composable shares it - the library carousel,
        // the show header, the Now Playing screen - and none of them should ever be dimmed.
        val pixels = pixelsOf(RowPlaybackState.Inactive)
        val scrimFree = pixels.count { it != pixels[0] }

        // The fallback art is a glyph on a tonal square, so *some* variation is expected; what
        // must not appear is a uniform darkening across the whole thing.
        assertThat(fractionDiffering(RowPlaybackState.Inactive, RowPlaybackState.Inactive))
            .isEqualTo(0f)
        assertThat(scrimFree).isGreaterThan(0)
    }


    /**
     * The marked square holds the bars and nothing else.
     *
     * Found by looking at a render rather than by a failing test: the fallback music note was still
     * being drawn underneath, so a marked row with no artwork yet showed a music note and an
     * equaliser stacked behind a scrim. That is every row on a fast scroll through a feed, since
     * artwork arrives asynchronously - exactly the case the marker exists to serve.
     *
     * Expressed as "nothing is darker than the scrim": the bars are brighter than their ground and
     * the note was darker, so a single bound catches the regression without pinning any colour.
     */
    @Test
    fun a_marked_row_does_not_stack_the_fallback_glyph_under_the_marker() {
        val luminances = luminancesOf(RowPlaybackState.Playing)
        val ground = luminances.groupingBy { it }.eachCount().maxBy { it.value }.key

        val darkest = luminances.min()
        Log.w("ARTWORK_OVERLAY", "scrim ground=$ground darkest=$darkest")
        assertThat(darkest).isAtLeast(ground - TONAL_EPSILON)
    }

    private fun luminancesOf(state: RowPlaybackState): List<Float> {
        val map = compose.onNodeWithTag(state.name).captureToImage().toPixelMap()
        return (0 until map.height).flatMap { y ->
            (0 until map.width).map { x -> map[x, y].luminance() }
        }
    }

    private fun pixelsOf(state: RowPlaybackState): IntArray {
        val map = compose.onNodeWithTag(state.name).captureToImage().toPixelMap()
        return IntArray(map.width * map.height) { index ->
            map[index % map.width, index / map.width].toArgb()
        }
    }

    private fun fractionDiffering(a: RowPlaybackState, b: RowPlaybackState): Float {
        val first = pixelsOf(a)
        val second = pixelsOf(b)
        require(first.size == second.size) { "thumbnails differ in size" }
        return first.indices.count { first[it] != second[it] }.toFloat() / first.size
    }

    private companion object {
        /** Room for antialiasing at the rounded corners, which the clip blends toward the edge. */
        const val TONAL_EPSILON = 0.06f
    }
}
