package com.solewis.podcaster.data.remote

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for a real bug: the JVM unit test suite runs against the host JDK's Xerces SAX
 * implementation, but Android ships its own Expat-based one, and they don't recognize the same
 * optional parser features. This exact feed (a real capture of feeds.transistor.fm/acquired) made
 * [RssParser] throw `SAXNotRecognizedException` on-device for
 * `http://apache.org/xml/features/nonvalidating/load-external-dtd` - a Xerces-specific feature
 * name that passes silently in every JVM unit test but fails only on a real device/emulator.
 * `RssParserTest` cannot catch this class of bug by construction; this test exists so a future
 * change to the parser's feature configuration gets caught here instead of during manual QA.
 */
@RunWith(AndroidJUnit4::class)
class RssParserOnDeviceTest {

    @Test
    fun parses_a_real_feed_on_the_actual_android_xml_parser_implementation() {
        val stream = InstrumentationRegistry.getInstrumentation().context.assets.open("feeds/acquired.xml")
        val feed = RssParser.parse(stream)

        assertThat(feed.channel.title).isEqualTo("Acquired")
        assertThat(feed.items).isNotEmpty()
    }
}
