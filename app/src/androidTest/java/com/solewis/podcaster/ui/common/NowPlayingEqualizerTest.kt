package com.solewis.podcaster.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On device, and reading pixels, because everything else about this glyph is testable without it
 * ever drawing anything. The screen tests assert its content description, which a [Canvas] that
 * painted nothing at all would satisfy perfectly - and a hand-drawn shape is exactly where
 * "compiles, composes, invisible" is a realistic outcome.
 *
 * Robolectric is not an option: it has no real graphics pipeline to read back.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingEqualizerTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun the_bars_are_actually_drawn() {
        compose.setContent {
            PodcasterTheme {
                // Padded white ground, so anything found is the glyph and not the window behind it.
                Box(Modifier.background(Color.White).padding(4.dp)) {
                    NowPlayingEqualizer(isPlaying = true)
                }
            }
        }

        val painted = paintedPixels("Now playing")

        // Three bars over three of the five columns, none shorter than 30% of the height, so a
        // third of the box is a floor no correct rendering falls under and no empty one reaches.
        assertThat(painted).isGreaterThan(0.2f)
        // And it is a glyph, not a filled rectangle - the gaps and the varying heights are the
        // whole readable shape of it.
        assertThat(painted).isLessThan(0.8f)
    }

    @Test
    fun a_paused_glyph_is_drawn_too_rather_than_vanishing_when_the_animation_stops() {
        compose.setContent {
            PodcasterTheme {
                Box(Modifier.background(Color.White).padding(4.dp)) {
                    NowPlayingEqualizer(isPlaying = false)
                }
            }
        }

        // The paused branch takes a different path entirely - no transition, a parked value - which
        // is the one most likely to draw nothing by accident.
        assertThat(paintedPixels("Paused here")).isGreaterThan(0.2f)
    }

    /** The fraction of the glyph's own bounds that is not the white background behind it. */
    private fun paintedPixels(description: String): Float {
        val pixels = compose.onNodeWithContentDescription(description).captureToImage().toPixelMap()
        var painted = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                if (pixels[x, y] != Color.White) painted++
            }
        }
        return painted.toFloat() / (pixels.width * pixels.height)
    }
}
