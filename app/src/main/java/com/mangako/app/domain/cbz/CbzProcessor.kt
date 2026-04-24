package com.mangako.app.domain.cbz

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Peeks into a .cbz (which is just a zip) to pull out ComicInfo.xml fields
 * without unpacking image data. We operate on the file in-place and never
 * rewrite its contents — the pipeline only renames it.
 */
class CbzProcessor {

    /**
     * Extract ComicInfo.xml into a flat variable map. Returns an empty map if
     * the archive has no ComicInfo.xml or the XML is malformed — the pipeline
     * should still run with just the filename.
     */
    fun extractMetadata(cbz: File): Map<String, String> {
        if (!cbz.exists() || !cbz.canRead()) return emptyMap()
        return try {
            ZipFile(cbz).use { zip ->
                val entry = zip.entries().asSequence()
                    .firstOrNull { it.name.equals("ComicInfo.xml", ignoreCase = true) }
                    ?: return emptyMap()
                zip.getInputStream(entry).use { parseComicInfo(it) }
            }
        } catch (t: Throwable) {
            emptyMap()
        }
    }

    private fun parseComicInfo(input: InputStream): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(input, null)
        }

        var depth = 0
        var currentTag: String? = null
        val text = StringBuilder()
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (depth == 2) {
                        currentTag = parser.name
                        text.setLength(0)
                    }
                }
                XmlPullParser.TEXT -> if (currentTag != null) text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (depth == 2 && currentTag != null) {
                        val v = text.toString().trim()
                        if (v.isNotEmpty()) out[KEY_MAP[currentTag!!.lowercase()] ?: currentTag!!.lowercase()] = v
                        currentTag = null
                    }
                    depth--
                }
                XmlPullParser.END_DOCUMENT -> return out
            }
        }
    }

    companion object {
        /**
         * Maps ComicInfo.xml element names (lowercased) to the variable names we
         * expose to the pipeline via %var%. Everything else is exposed using its
         * lowercase element name as a fallback.
         */
        private val KEY_MAP = mapOf(
            "title" to "title",
            "series" to "series",
            "number" to "number",
            "writer" to "writer",
            "penciller" to "penciller",
            "colorist" to "colorist",
            "letterer" to "letterer",
            "inker" to "inker",
            "publisher" to "publisher",
            "genre" to "genre",
            "tags" to "tags",
            "characters" to "characters",
            "year" to "year",
            "month" to "month",
            "day" to "day",
            "summary" to "summary",
            "languageiso" to "language",
        )
    }
}
