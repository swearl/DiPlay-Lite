package com.shilapi.xcertplay

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Strictly parse published release entries from GitHub's official Atom feed. No API token needed.
 * Tags must look like "v{versionName}-{versionCode}" (e.g. v0.3.12-29); anything else is ignored.
 */
internal object DiPlayAtomSource {
    private const val REPO = "https://github.com/swearl/DiPlay-Lite"
    data class Entry(val tag: String, val versionName: String, val versionCode: Int,
                     val notes: String)

    fun newest(xml: String, installedVersionCode: Int): Entry? {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))
        var entry = false
        var id = ""
        var href = ""
        var title = ""
        var notes = ""
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "entry" -> {
                        entry = true
                        id = ""
                        href = ""
                        title = ""
                        notes = ""
                    }
                    "id" -> if (entry) id = parser.nextText()
                    "title" -> if (entry) title = parser.nextText()
                    "content" -> if (entry) {
                        notes = parser.nextText()
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("\\s+"), " ").trim().take(6000)
                    }
                    "link" -> if (entry && parser.getAttributeValue(null, "rel") == "alternate") {
                        href = parser.getAttributeValue(null, "href") ?: ""
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "entry") {
                    entry = false
                    val tag = id.substringAfterLast('/')
                    val parsed = DiPlayUpdateClient.parseTag(tag)
                    if (id.startsWith("tag:github.com,2008:Repository/") &&
                        href == "$REPO/releases/tag/$tag" &&
                        parsed != null &&
                        parsed.second > installedVersionCode
                    ) return Entry(tag, parsed.first, parsed.second,
                        notes.ifBlank { title }.take(6000))
                }
            }
            parser.next()
        }
        return null
    }
}
