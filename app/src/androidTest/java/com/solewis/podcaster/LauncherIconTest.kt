package com.solewis.podcaster

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The launcher icon, rendered by the platform rather than inspected as XML.
 *
 * Two jobs. It checks the things that go silently wrong with an adaptive icon - a foreground that
 * ends up empty, a background that ends up transparent, content that strays outside the circle
 * every launcher mask keeps - none of which a build failure would ever catch, and all of which look
 * fine in the XML.
 *
 * It also writes the Play Store's required 512px icon to the app's external files directory, from
 * the same drawables the launcher uses, so that asset cannot drift away from the real icon:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.solewis.podcaster.LauncherIconTest
 *   adb shell run-as com.solewis.podcaster cat files/play-store-icon-512.png > icon-512.png
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 108dp of adaptive-icon viewport, at the 512px Play Store asks for. */
    private val size = 512

    /**
     * The artwork, unmasked - both adaptive layers drawn full-bleed.
     *
     * Not `R.mipmap.ic_launcher`: an `AdaptiveIconDrawable` applies the device's own mask when it
     * draws, so that route hands back a circle with transparent corners. That is right for a
     * launcher and wrong for the Play Store, which wants a full square and rounds it itself.
     */
    private fun render(): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground).forEach { id ->
            val layer = requireNotNull(ResourcesCompat.getDrawable(context.resources, id, null))
            layer.setBounds(0, 0, size, size)
            layer.draw(canvas)
        }
        return bitmap
    }

    @Test
    fun the_icon_renders_and_writes_the_play_store_asset() {
        val bitmap = render()
        val out = File(context.filesDir, "play-store-icon-512.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertThat(out.length()).isGreaterThan(0L)
    }

    @Test
    fun the_background_covers_the_whole_icon() {
        val bitmap = render()

        // Every corner opaque. A background that does not bleed to the edges leaves the launcher's
        // mask cutting through transparency instead of through the artwork, which shows up as a
        // chipped edge on some masks and not others - and leaves the Play Store icon, which is not
        // masked at all, with see-through corners.
        for ((x, y) in listOf(2 to 2, size - 3 to 2, 2 to size - 3, size - 3 to size - 3)) {
            assertThat(Color.alpha(bitmap.getPixel(x, y))).isEqualTo(255)
        }
    }

    @Test
    fun the_microphone_is_actually_there_and_lighter_than_the_ball() {
        val bitmap = render()
        val centre = bitmap.getPixel(size / 2, size / 2)
        // A point on the ball well clear of the mic, at about 4 o'clock.
        val ball = bitmap.getPixel((size * 0.78).toInt(), (size * 0.62).toInt())

        // The mic reads *through* the mesh rather than sitting on top of it, so it is deliberately
        // not white - but it still has to be clearly lighter than what surrounds it, or the icon is
        // a plain disc at launcher size.
        assertThat(luminance(centre) - luminance(ball)).isGreaterThan(60.0)
    }

    @Test
    fun no_part_of_the_microphone_can_be_clipped_by_a_launcher_mask() {
        val bitmap = render()
        // Masks keep the central 66 of 108dp. Outside that radius there must be windscreen only:
        // anything of the mic out there is liable to be sliced off, and on a round mask it would be.
        val radius = size * (66.0 / 108.0) / 2
        val centre = size / 2.0

        var brightestOutside = 0.0
        for (y in 0 until size step 3) {
            for (x in 0 until size step 3) {
                val dx = x - centre
                val dy = y - centre
                if (dx * dx + dy * dy < radius * radius) continue
                brightestOutside = maxOf(brightestOutside, luminance(bitmap.getPixel(x, y)))
            }
        }

        // The mic is near-white; the ball with its highlight never approaches that. Anything this
        // bright out here would be the mic having escaped the safe area.
        assertThat(brightestOutside).isLessThan(190.0)
    }

    private fun luminance(c: Int) =
        0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
}
