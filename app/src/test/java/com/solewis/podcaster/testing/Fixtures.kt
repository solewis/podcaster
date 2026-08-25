package com.solewis.podcaster.testing

/**
 * Loads a captured feed from `src/test/resources/feeds`. These are real captures, not synthetic
 * XML - see the assertions in `RssParserTest` for the specific real-world quirks each one carries.
 */
object Fixtures {

    fun feedText(name: String): String = feedBytes(name).decodeToString()

    fun feedBytes(name: String): ByteArray =
        checkNotNull(Fixtures::class.java.classLoader?.getResourceAsStream("feeds/$name")) {
            "Missing test fixture: feeds/$name"
        }.use { it.readBytes() }
}
