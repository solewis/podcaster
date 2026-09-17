package com.solewis.podcaster.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The rail is a `drawBehind`, so it has no layout node and nothing in the semantics tree stands for
 * it. Reading pixels is the only way to know it is there at all - and the version before this one
 * had a real node that resolved to zero height while remaining perfectly findable, which is the
 * failure this guards.
 */
@RunWith(AndroidJUnit4::class)
class NowPlayingRailTest {

    @get:Rule
    val compose = createComposeRule()

    private var accent: Int = 0

    @Before
    fun setUp() {
        compose.setContent {
            PodcasterTheme {
                accent = MaterialTheme.colorScheme.primary.toArgb()
                // A Column, not three loose Boxes: without a layout to place them they stack on
                // top of one another, and capturing the unmarked one then picked up the railed
                // ones drawn over it - 158 accent rows where none were expected.
                Column {
                    RowPlaybackState.entries.forEach { state ->
                        Box(
                            Modifier
                                .size(120.dp, 60.dp)
                                .background(Color.White)
                                .nowPlayingRail(state, MaterialTheme.colorScheme.primary)
                                .testTag(state.name)
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun a_marked_row_gets_an_accent_rail_down_its_left_edge() {
        val pixels = pixelsOf(RowPlaybackState.Playing)

        // Top to bottom, so a rail that drew only where the content happened to be would fail.
        assertThat(pixels.leftEdgeRows).isEqualTo(pixels.height)
        assertThat(pixels.at(x = 0, y = pixels.height / 2)).isEqualTo(accent)
        // And it is a rail, not a wash over the whole row.
        assertThat(pixels.at(x = pixels.width / 2, y = pixels.height / 2)).isEqualTo(Color.White.toArgb())
    }

    @Test
    fun a_paused_row_is_railed_the_same_way() {
        // Paused is still the loaded episode, and finding it again is the whole point of marking it.
        assertThat(pixelsOf(RowPlaybackState.Paused).leftEdgeRows)
            .isEqualTo(pixelsOf(RowPlaybackState.Paused).height)
    }

    @Test
    fun an_unmarked_row_gets_nothing() {
        val pixels = pixelsOf(RowPlaybackState.Inactive)

        assertThat(pixels.leftEdgeRows).isEqualTo(0)
    }

    private fun pixelsOf(state: RowPlaybackState): Bitmap {
        val map = compose.onNodeWithTag(state.name).captureToImage().toPixelMap()
        return Bitmap(
            width = map.width,
            height = map.height,
            argb = IntArray(map.width * map.height) { index ->
                map[index % map.width, index / map.width].toArgb()
            },
            accent = accent
        )
    }

    private class Bitmap(val width: Int, val height: Int, val argb: IntArray, val accent: Int) {
        fun at(x: Int, y: Int): Int = argb[y * width + x]

        /** How many of the rows have the accent colour hard against their left edge. */
        val leftEdgeRows: Int get() = (0 until height).count { at(0, it) == accent }
    }
}
