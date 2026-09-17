package com.solewis.podcaster.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.solewis.podcaster.data.repo.SubscriptionRepository
import com.solewis.podcaster.ui.theme.PodcasterTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a long description is visibly cut off, ellipsis and all.
 *
 * On device because this cannot be tested anywhere else: Robolectric has no real font metrics, so
 * every string measures as one short line there and nothing ever overflows `maxLines` - a preview
 * that silently stopped truncating would pass the entire JVM suite.
 *
 * Which is exactly what happened. The preview was trimmed to 120 characters as an optimisation, two
 * lines hold about that many, so the text *fitted* and the ellipsis quietly disappeared. Reported
 * from the phone, invisible to every test that existed.
 */
@RunWith(AndroidJUnit4::class)
class EpisodeDescriptionPreviewTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The regression itself, stated as a property of the code rather than of a rendering.
     *
     * The preview was trimmed to roughly what two lines hold before being handed to [Text], which
     * made it fit, so no ellipsis was drawn. Asserting on the *rendering* cannot catch that
     * reliably: whether a given character count overflows depends on the row's width, the font
     * scale and the actual glyphs, and at both 280dp and 380dp the trimmed string still wrapped
     * past two lines and still truncated - the test passed with the bug reapplied, twice.
     *
     * So this asserts the thing that is actually true at every width: truncation belongs to the
     * text layout, which is the only party that knows how much fits, and this composable must hand
     * it the whole string. Any `take(n)` fails here immediately.
     */
    @Test
    fun the_whole_preview_reaches_the_layout_rather_than_being_trimmed_first() {
        val text = "word ".repeat(200).take(SubscriptionRepository.DESCRIPTION_PREVIEW_LENGTH)
        showPreview(text)

        assertThat(textLayout().layoutInput.text.text).isEqualTo(text)
    }

    @Test
    fun a_preview_as_long_as_the_column_stores_is_cut_off_with_an_ellipsis() {
        // The real bound, not a made-up one: this is the longest string a row can ever be handed,
        // so if this one is not truncated then nothing ever is.
        val text = "word ".repeat(200).take(SubscriptionRepository.DESCRIPTION_PREVIEW_LENGTH)
        showPreview(text)

        val layout = textLayout()
        assertThat(layout.lineCount).isEqualTo(2)
        assertThat(visibleCharacters(layout)).isLessThan(text.length)
    }

    @Test
    fun a_short_preview_is_shown_whole() {
        val text = "Two sentences worth."
        showPreview(text)

        val layout = textLayout()
        assertThat(layout.lineCount).isEqualTo(1)
        assertThat(visibleCharacters(layout)).isEqualTo(text.length)
    }

    /**
     * How much of the string actually made it onto the screen.
     *
     * Deliberately not `hasVisualOverflow`, which was the obvious choice and is wrong here: it is
     * also true when the laid-out width merely rounds past its constraint, so it reported overflow
     * for a single short line that fitted perfectly (measured: one line, 324px wide against a 735px
     * limit, and still "overflowing"). Asking where the visible text ends answers the actual
     * question - an ellipsis is drawn exactly when the string outruns what was shown.
     */
    private fun visibleCharacters(layout: TextLayoutResult): Int =
        layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)

    /**
     * A real row's width: the description runs the full width of the row, flush with the left edge
     * of the artwork above it, so this is a phone's width less the row's own 16dp of padding either
     * side.
     *
     * Width is the whole test, not a detail. At a narrower 280dp the trimmed-to-120-characters bug
     * still wrapped past two lines and still truncated, so the test passed with the regression
     * reapplied - the fault only appears once a row is wide enough for 120 characters to *fit*.
     */
    private fun showPreview(text: String) {
        compose.setContent {
            PodcasterTheme {
                Box(Modifier.width(ROW_WIDTH)) {
                    EpisodeDescriptionPreview(text, Modifier.testTag(TAG))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(TAG).fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(results)
        return results.single()
    }

    private companion object {
        const val TAG = "description-under-test"
        val ROW_WIDTH = 380.dp
    }
}
