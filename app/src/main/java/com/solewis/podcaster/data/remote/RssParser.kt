package com.solewis.podcaster.data.remote

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Streaming RSS/podcast feed parser.
 *
 * Uses SAX rather than a DOM/tree parser: one of the real feeds captured while building this app
 * is 18.5 MB with 2952 items, and a full in-memory tree for that is wasteful at best. SAX is also
 * deliberately preferred over Android's `android.util.Xml` pull parser here: that class is part
 * of the Android framework and is stubbed out (throws) in a plain JVM unit test without
 * Robolectric, whereas `javax.xml.parsers` (SAX) has a real, working implementation on both the
 * JVM (used by `./gradlew test`) and on-device - so the parser and its tests run identically in
 * both places.
 *
 * Never throws on malformed input from the caller's perspective in the sense that matters: any
 * parse failure is wrapped in [MalformedFeedException], so a caller can catch exactly one type
 * and preserve existing data rather than propagating an XML-library-specific exception.
 */
object RssParser {

    private const val ITUNES_NS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val CONTENT_NS = "http://purl.org/rss/1.0/modules/content/"
    private const val ATOM_NS = "http://www.w3.org/2005/Atom"

    class MalformedFeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun parse(input: InputStream): ParsedFeed {
        val handler = FeedHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                // Feeds are untrusted input - never resolve external entities (the XXE vector).
                // Deliberately only the two standard SAX2 core features here, not Xerces-specific
                // ones like "http://apache.org/xml/features/nonvalidating/load-external-dtd":
                // verified on a real feed (feeds.transistor.fm/acquired, which has a DOCTYPE-less
                // stylesheet PI that triggered this) that Android's on-device parser is
                // Expat-based, not Xerces, and throws SAXNotRecognizedException for that feature -
                // a difference the JVM unit tests can't catch, since they run against the host
                // JDK's Xerces implementation. These two are part of the SAX spec itself and are
                // sufficient: entity expansion is the actual attack surface, and both are disabled.
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (e: Exception) {
            throw MalformedFeedException("Failed to parse feed: ${e.message}", e)
        }
        return handler.result()
    }

    private class FeedHandler : DefaultHandler() {
        // Channel-level state.
        private var title: String? = null
        private var description: String? = null
        private var author: String? = null
        private var imageUrl: String? = null
        private var link: String? = null
        private var language: String? = null
        private var itunesType: String? = null
        private var newFeedUrl: String? = null
        private var nextPageUrl: String? = null

        private val items = mutableListOf<ParsedItem>()
        private var nextFeedPosition = 0

        // Location context.
        private var sawRootElement = false
        private var inChannel = false
        private var inItem = false
        private var inChannelImage = false // standard <image><url>, distinct from itunes:image

        // Per-item accumulators, reset at the start of every <item>.
        private var itemTitle: String? = null
        private var itemGuid: String? = null
        private var itemGuidIsPermaLink = true
        private var itemEnclosureUrl: String? = null
        private var itemEnclosureBytes: Long? = null
        private var itemEnclosureMimeType: String? = null
        private var itemPubDateRaw: String? = null
        private var itemDurationRaw: String? = null
        private var itemDescription: String? = null
        private var itemContentEncoded: String? = null
        private var itemImageUrl: String? = null
        private var itemEpisodeNumber: Int? = null
        private var itemSeason: Int? = null
        private var itemEpisodeType: String = "full"
        private var itemWebPageUrl: String? = null

        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val ns = uri.orEmpty()
            val name = localName ?: qName.orEmpty()
            text.setLength(0)

            if (!sawRootElement) {
                sawRootElement = true
                if (name != "rss" && name != "feed") {
                    // Well-formed XML that just isn't a podcast feed - e.g. a JSON-error-as-XML
                    // response captured from a real 404. Reject explicitly rather than silently
                    // returning an empty feed, which would look like "show has zero episodes".
                    throw org.xml.sax.SAXException("Root element is <$name>, expected <rss> or <feed>")
                }
            }

            when {
                name == "channel" -> inChannel = true
                name == "item" -> {
                    inItem = true
                    resetItemAccumulators()
                }
                name == "image" && ns != ITUNES_NS && inChannel && !inItem -> inChannelImage = true
                name == "image" && ns == ITUNES_NS -> {
                    val href = attributes?.getValue("href")
                    if (inItem) itemImageUrl = href else if (imageUrl == null) imageUrl = href
                }
                name == "enclosure" && inItem -> {
                    itemEnclosureUrl = attributes?.getValue("url")
                    itemEnclosureBytes = attributes?.getValue("length")?.toLongOrNull()
                    itemEnclosureMimeType = attributes?.getValue("type")
                }
                name == "guid" && inItem -> {
                    val permaLinkAttr = attributes?.getValue("isPermaLink")
                    itemGuidIsPermaLink = permaLinkAttr == null || permaLinkAttr == "true"
                }
                name == "link" && ns == ATOM_NS -> {
                    val rel = attributes?.getValue("rel")
                    val href = attributes?.getValue("href")
                    if (rel == "next" && href != null) nextPageUrl = href
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (ch != null) text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val ns = uri.orEmpty()
            val name = localName ?: qName.orEmpty()
            val value = text.toString().trim().takeIf { it.isNotEmpty() }
            text.setLength(0)

            when {
                name == "item" -> {
                    items.add(buildItem())
                    inItem = false
                }
                name == "channel" -> inChannel = false
                name == "image" && ns != ITUNES_NS -> inChannelImage = false
                inItem -> handleItemChildEnd(name, ns, value)
                inChannel -> handleChannelChildEnd(name, ns, value)
            }
        }

        private fun handleChannelChildEnd(name: String, ns: String, value: String?) {
            when {
                name == "title" && ns.isEmpty() -> title = value
                name == "description" && ns.isEmpty() -> description = value
                name == "author" && ns == ITUNES_NS -> author = value
                name == "summary" && ns == ITUNES_NS && description == null -> description = value
                name == "subtitle" && ns == ITUNES_NS && description == null -> description = value
                name == "url" && inChannelImage -> if (imageUrl == null) imageUrl = value
                name == "link" && ns.isEmpty() -> link = value
                name == "language" && ns.isEmpty() -> language = value
                name == "type" && ns == ITUNES_NS -> itunesType = value
                name == "new-feed-url" && ns == ITUNES_NS -> newFeedUrl = value
            }
        }

        private fun handleItemChildEnd(name: String, ns: String, value: String?) {
            when {
                name == "title" && ns.isEmpty() -> itemTitle = value
                name == "guid" -> itemGuid = value
                name == "pubDate" && ns.isEmpty() -> itemPubDateRaw = value
                name == "duration" && ns == ITUNES_NS -> itemDurationRaw = value
                name == "description" && ns.isEmpty() -> itemDescription = value
                name == "encoded" && ns == CONTENT_NS -> itemContentEncoded = value
                name == "summary" && ns == ITUNES_NS && itemDescription == null -> itemDescription = value
                name == "episode" && ns == ITUNES_NS -> itemEpisodeNumber = value?.toIntOrNull()
                name == "season" && ns == ITUNES_NS -> itemSeason = value?.toIntOrNull()
                name == "episodeType" && ns == ITUNES_NS -> itemEpisodeType = value ?: "full"
                name == "link" && ns.isEmpty() -> itemWebPageUrl = value
            }
        }

        private fun resetItemAccumulators() {
            itemTitle = null
            itemGuid = null
            itemGuidIsPermaLink = true
            itemEnclosureUrl = null
            itemEnclosureBytes = null
            itemEnclosureMimeType = null
            itemPubDateRaw = null
            itemDurationRaw = null
            itemDescription = null
            itemContentEncoded = null
            itemImageUrl = null
            itemEpisodeNumber = null
            itemSeason = null
            itemEpisodeType = "full"
            itemWebPageUrl = null
        }

        private fun buildItem(): ParsedItem = ParsedItem(
            feedPosition = nextFeedPosition++,
            title = itemTitle,
            guid = itemGuid,
            guidIsPermaLink = itemGuidIsPermaLink,
            enclosureUrl = itemEnclosureUrl,
            enclosureBytes = itemEnclosureBytes,
            enclosureMimeType = itemEnclosureMimeType,
            pubDateRaw = itemPubDateRaw,
            durationRaw = itemDurationRaw,
            descriptionHtml = itemContentEncoded ?: itemDescription,
            imageUrl = itemImageUrl,
            itunesEpisodeNumber = itemEpisodeNumber,
            itunesSeason = itemSeason,
            episodeType = itemEpisodeType,
            webPageUrl = itemWebPageUrl
        )

        fun result(): ParsedFeed = ParsedFeed(
            channel = ParsedChannel(
                title = title,
                description = description,
                author = author,
                imageUrl = imageUrl,
                link = link,
                language = language,
                itunesType = itunesType,
                newFeedUrl = newFeedUrl,
                nextPageUrl = nextPageUrl
            ),
            items = items
        )
    }
}
