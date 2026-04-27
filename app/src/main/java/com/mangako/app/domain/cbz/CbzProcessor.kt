package com.mangako.app.domain.cbz

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads (and rewrites) ComicInfo.xml inside a .cbz without touching the
 * image entries. The .cbz format is just a zip; we stream image entries
 * straight through to the rewritten archive and only swap the
 * ComicInfo.xml bytes.
 */
class CbzProcessor {

    /**
     * Detection-time extract: peek inside the .cbz, pull ComicInfo.xml
     * variables AND the first image bytes in a single streaming pass.
     * Used by the watcher when a new file shows up so the Inbox card
     * can render with a real thumbnail and a pipeline-simulated title
     * before the user ever taps Process.
     *
     * Streaming (ZipInputStream) instead of random-access (ZipFile)
     * means we don't have to copy the .cbz to local cache first —
     * scans of cloud-mounted folders stay snappy.
     */
    data class DetectionInfo(
        val metadata: Map<String, String>,
        val firstImage: ByteArray?,
    )

    fun extractDetectionInfo(input: InputStream): DetectionInfo {
        var metadata: Map<String, String>? = null
        var firstImage: ByteArray? = null
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        metadata == null && name.equals(COMIC_INFO_NAME, ignoreCase = true) -> {
                            metadata = runCatching { parseComicInfo(zip) }.getOrDefault(emptyMap())
                        }
                        firstImage == null && isImageEntry(name) -> {
                            firstImage = runCatching { zip.readBytes() }.getOrNull()
                        }
                    }
                    if (metadata != null && firstImage != null) break
                }
            }
        } catch (_: Throwable) {
            // Fall through with whatever we managed to read.
        }
        return DetectionInfo(metadata.orEmpty(), firstImage)
    }

    /**
     * Decode [imageBytes] (the first-page image of a .cbz), scale it
     * down to a thumbnail-friendly width, and write the recompressed
     * JPEG to [outFile]. Returns true on success. Used by the watcher
     * so each Inbox card can show a small cover preview without
     * dragging around the original 1-3MB chapter page.
     */
    fun saveThumbnail(imageBytes: ByteArray, outFile: File, targetWidth: Int = 320): Boolean = try {
        val bitmap: Bitmap? = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        if (bitmap == null) {
            false
        } else {
            val scaled = if (bitmap.width > targetWidth) {
                val height = (bitmap.height.toFloat() * targetWidth / bitmap.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, targetWidth, height, true)
            } else bitmap
            outFile.parentFile?.mkdirs()
            outFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            true
        }
    } catch (_: Throwable) {
        false
    }

    private fun isImageEntry(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") ||
            lower.endsWith(".gif") || lower.endsWith(".avif")
    }

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

    /**
     * Rewrite [cbz] in place so each entry in [fieldsToSet] becomes a
     * `<KeyName>value</KeyName>` element under the root `<ComicInfo>`. Used
     * by the pipeline worker to inject the renamed filename into
     * `<Title>`, so a downstream reader (LANraragi auto-extraction, Mihon
     * resync, etc.) sees a consistent title.
     *
     * Approach: stream every non-ComicInfo entry from the source zip into
     * a temp zip unchanged, then write a new ComicInfo.xml at the end and
     * atomically replace the source. Streaming avoids buffering image
     * data — a typical chapter is 20-50MB of jpeg/webp we don't want in
     * the JVM heap.
     *
     * No-op (returns false) if the file isn't writable or anything goes
     * wrong. Original archive is left intact.
     */
    fun updateMetadata(cbz: File, fieldsToSet: Map<String, String>): Boolean {
        if (!cbz.exists() || !cbz.canWrite() || fieldsToSet.isEmpty()) return false
        val temp = File(cbz.parentFile ?: return false, cbz.name + ".rewrite")
        return try {
            val originalXml = readComicInfoXml(cbz)
            val updatedXml = mergeComicInfoXml(originalXml, fieldsToSet)

            ZipFile(cbz).use { zipIn ->
                ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zipOut ->
                    val entries = zipIn.entries().asSequence().toList()
                    for (entry in entries) {
                        if (entry.name.equals(COMIC_INFO_NAME, ignoreCase = true)) continue
                        // Rebuild the entry rather than copying — copying
                        // can fail if the source has STORED entries with
                        // out-of-band CRCs the JDK rejects on re-open.
                        val out = ZipEntry(entry.name).apply {
                            time = entry.time
                            comment = entry.comment
                        }
                        zipOut.putNextEntry(out)
                        zipIn.getInputStream(entry).use { it.copyTo(zipOut) }
                        zipOut.closeEntry()
                    }
                    zipOut.putNextEntry(ZipEntry(COMIC_INFO_NAME))
                    zipOut.write(updatedXml.toByteArray(Charsets.UTF_8))
                    zipOut.closeEntry()
                }
            }
            // Atomic-ish replace: delete original then move temp into
            // place. We do this rather than rename-over because some
            // filesystems (FAT32 on SD cards) refuse a rename across an
            // existing destination.
            if (!cbz.delete()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(cbz)) {
                temp.delete()
                return false
            }
            true
        } catch (t: Throwable) {
            temp.delete()
            false
        }
    }

    private fun readComicInfoXml(cbz: File): String? = runCatching {
        ZipFile(cbz).use { zip ->
            val entry = zip.entries().asSequence()
                .firstOrNull { it.name.equals(COMIC_INFO_NAME, ignoreCase = true) }
                ?: return@use null
            zip.getInputStream(entry).use { it.bufferedReader(Charsets.UTF_8).readText() }
        }
    }.getOrNull()

    /**
     * Replace each `<Key>...</Key>` element with the new value, or insert
     * one before `</ComicInfo>` if it doesn't exist. Regex-based to avoid
     * dragging in a real XML library — the file is small and ComicInfo's
     * schema is flat (no nested elements with the same names as the
     * top-level ones we touch).
     *
     * If [originalXml] is null we synthesise a minimal ComicInfo.xml
     * carrying just the requested fields.
     */
    private fun mergeComicInfoXml(originalXml: String?, fieldsToSet: Map<String, String>): String {
        if (originalXml == null) {
            return buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                append("<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
                append(" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n")
                fieldsToSet.forEach { (k, v) ->
                    append("  <").append(k).append(">").append(escapeXml(v)).append("</").append(k).append(">\n")
                }
                append("</ComicInfo>\n")
            }
        }
        var result: String = originalXml
        fieldsToSet.forEach { (key, value) ->
            val escaped = escapeXml(value)
            // Match both <Title>...</Title> and self-closing <Title/>. We
            // can be lenient about whitespace/case because ComicInfo writers
            // (Mihon, ComicInfo creator, etc.) all follow the canonical
            // PascalCase convention.
            val present = Regex("<$key>[^<]*</$key>", RegexOption.IGNORE_CASE)
            val selfClosing = Regex("<$key\\s*/>", RegexOption.IGNORE_CASE)
            when {
                present.containsMatchIn(result) ->
                    result = present.replace(result, "<$key>$escaped</$key>")
                selfClosing.containsMatchIn(result) ->
                    result = selfClosing.replace(result, "<$key>$escaped</$key>")
                else -> {
                    val close = Regex("</ComicInfo\\s*>", RegexOption.IGNORE_CASE)
                    result = close.replace(result, "  <$key>$escaped</$key>\n</ComicInfo>")
                }
            }
        }
        return result
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

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
        private const val COMIC_INFO_NAME = "ComicInfo.xml"

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
