package com.mangako.app.domain.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
        var bestImageName: String? = null
        var bestImage: ByteArray? = null
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    when {
                        metadata == null && name.equals(COMIC_INFO_NAME, ignoreCase = true) -> {
                            metadata = runCatching { parseComicInfo(zip) }.getOrDefault(emptyMap())
                        }
                        // ZIP entries aren't guaranteed to be stored in alphabetical order;
                        // a CBZ may physically place page 047 before page 001 if the archiver
                        // added them out of order. Track the lexicographically smallest image
                        // name and read its bytes — that's the actual cover (001.jpg /
                        // cover.jpg) by CBZ naming convention.
                        isImageEntry(name) -> {
                            val current = bestImageName
                            if (current == null || compareImagePaths(name, current) < 0) {
                                val bytes = runCatching { zip.readBytes() }.getOrNull()
                                if (bytes != null) {
                                    bestImageName = name
                                    bestImage = bytes
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Fall through with whatever we managed to read.
        }
        return DetectionInfo(metadata.orEmpty(), bestImage)
    }

    /**
     * Single high-level entry point for detection. Given a SAF / file content
     * URI, returns the cover bytes + ComicInfo metadata for that .cbz —
     * regardless of whether the underlying provider streams cleanly or drops
     * bytes mid-read.
     *
     * Strategy: try the cheap streaming pass first ([extractDetectionInfo]),
     * and if it yields no cover, copy the archive to a temp file in
     * [cacheDir] and retry with the random-access variant
     * ([extractDetectionInfoFromFile]). The local copy gives us a stable
     * central directory + per-entry random access, which always works once
     * the bytes are on local disk. The temp file is cleaned up before we
     * return.
     *
     * This is what every site that needs detection should call. The two
     * lower-level variants stay public for tests + the worker's
     * already-local code path.
     */
    fun extractDetectionFromUri(context: Context, uri: Uri): DetectionInfo {
        val streamed = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractDetectionInfo(input)
            }
        }.getOrNull()
        // Take the streaming pass only if it produced BOTH a cover and
        // metadata. Some CBZs stream the cover image fine but the
        // ZipInputStream reader misses ComicInfo.xml because it appears
        // *after* the central directory in entry order — we need the
        // random-access ZipFile path to read those reliably. Returning
        // here with empty metadata used to leave the Inbox card title-less
        // until processing time, which was a confusing user experience.
        if (streamed != null && streamed.firstImage != null && streamed.metadata.isNotEmpty()) {
            return streamed
        }

        val temp = File(context.cacheDir, "detect_${System.nanoTime()}.cbz")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return streamed ?: DetectionInfo(emptyMap(), null)
            val viaFile = extractDetectionInfoFromFile(temp)
            DetectionInfo(
                metadata = viaFile.metadata.takeIf { it.isNotEmpty() }
                    ?: streamed?.metadata.orEmpty(),
                firstImage = viaFile.firstImage ?: streamed?.firstImage,
            )
        } catch (_: Throwable) {
            streamed ?: DetectionInfo(emptyMap(), null)
        } finally {
            temp.delete()
        }
    }

    /**
     * Random-access detection variant for when [extractDetectionInfo] over a
     * stream produces nothing (cloud-mounted SAF URI dropping bytes mid-read,
     * malformed central directory streamed in a partial state, etc.). Reads
     * the central directory once via [ZipFile], picks the lexicographically
     * smallest image entry by page name, and returns just that entry's bytes
     * + the parsed ComicInfo. Costs a local copy of the .cbz before this is
     * called — much more robust but only worth the IO when streaming has
     * already failed.
     */
    fun extractDetectionInfoFromFile(cbz: File): DetectionInfo {
        if (!cbz.exists() || !cbz.canRead()) return DetectionInfo(emptyMap(), null)
        return try {
            ZipFile(cbz).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val metadata = entries
                    .firstOrNull { it.name.equals(COMIC_INFO_NAME, ignoreCase = true) }
                    ?.let { runCatching { zip.getInputStream(it).use(::parseComicInfo) }.getOrNull() }
                    .orEmpty()
                val cover = entries
                    .filter { isImageEntry(it.name) }
                    .minWithOrNull(Comparator { a, b -> compareImagePaths(a.name, b.name) })
                val bytes = cover?.let {
                    runCatching { zip.getInputStream(it).use(InputStream::readBytes) }.getOrNull()
                }
                DetectionInfo(metadata, bytes)
            }
        } catch (_: Throwable) {
            DetectionInfo(emptyMap(), null)
        }
    }

    /** Compare two zip entry paths the way a CBZ reader would order pages —
     *  case-insensitive and using the page filename as the primary key (not
     *  the directory) so a `Chapter/001.jpg` and `001.jpg` at the root sort
     *  alongside their numeric page number. */
    private fun compareImagePaths(a: String, b: String): Int {
        val pageA = a.substringAfterLast('/')
        val pageB = b.substringAfterLast('/')
        val byPage = pageA.compareTo(pageB, ignoreCase = true)
        return if (byPage != 0) byPage else a.compareTo(b, ignoreCase = true)
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
    fun updateMetadata(
        cbz: File,
        fieldsToSet: Map<String, String>,
        fieldsToRemove: Set<String> = emptySet(),
    ): Boolean {
        if (!cbz.exists() || !cbz.canWrite()) return false
        if (fieldsToSet.isEmpty() && fieldsToRemove.isEmpty()) return false
        val temp = File(cbz.parentFile ?: return false, cbz.name + ".rewrite")
        return try {
            val originalXml = readComicInfoXml(cbz)
            // Removing from a file with no ComicInfo.xml is a no-op — there's
            // nothing to strip and synthesizing a near-empty doc just to leave
            // out a few elements would add noise to the archive.
            if (originalXml == null && fieldsToSet.isEmpty()) return false
            val updatedXml = mergeComicInfoXml(originalXml, fieldsToSet, fieldsToRemove)

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
    private fun mergeComicInfoXml(
        originalXml: String?,
        fieldsToSet: Map<String, String>,
        fieldsToRemove: Set<String> = emptySet(),
    ): String {
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
        // Apply removals first so a field that's been both removed and set
        // (shouldn't happen, but defensive) ends up with the set value.
        fieldsToRemove.forEach { key ->
            // Strip both <Key>...</Key> and self-closing <Key/>, eating the
            // surrounding same-line whitespace and at most one trailing
            // newline so the doc doesn't end up with a pile of blank lines.
            // Carefully avoid `\\s*` next to the newline — it would greedily
            // gobble the indentation of the FOLLOWING line and break the
            // surviving elements' formatting.
            val present = Regex("[ \\t]*<$key>[^<]*</$key>[ \\t]*\\n?", RegexOption.IGNORE_CASE)
            val selfClosing = Regex("[ \\t]*<$key\\s*/>[ \\t]*\\n?", RegexOption.IGNORE_CASE)
            result = present.replace(result, "")
            result = selfClosing.replace(result, "")
        }
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
         * The closed set of pipeline-variable names that map to a real
         * ComicInfo.xml element. The Inbox edit sheet uses this as its
         * allowlist — the sheet is a "virtual ComicInfo" the pipeline
         * reads as input, so anything it accepts must be a real ComicInfo
         * field. Pipeline-computed variables (e.g. `event`, `extra_tags`)
         * are NOT here; they're outputs of upstream rules, not inputs.
         *
         * Order matters — the picker renders fields in this order, so
         * keep the canonically-most-edited fields up top and group
         * person fields together.
         */
        val COMIC_INFO_VARIABLES: List<String> = listOf(
            "title",
            "series",
            "number",
            "writer",
            "penciller",
            "colorist",
            "letterer",
            "inker",
            "publisher",
            "language",
            "genre",
            "tags",
            "characters",
            "year",
            "month",
            "day",
            "summary",
        )

        /**
         * Canonical PascalCase ComicInfo.xml element name for a pipeline
         * variable. Used as the on-screen label for the Edit sheet so
         * users see exactly what's in their .cbz — `LanguageISO` not
         * `Language`, `Title` not `My Title`. Falls back to the bare
         * variable name for non-ComicInfo entries (custom %vars% etc.)
         * which is honest about there being no ComicInfo equivalent.
         */
        fun comicInfoLabelFor(variable: String): String = ELEMENT_NAMES[variable] ?: variable

        /**
         * Translate user-facing pipeline-variable names (e.g. "language",
         * "title") into the ComicInfo.xml element names that should be
         * stripped from the archive when the user removes the field via the
         * Inbox edit sheet. Most variables match their PascalCase element
         * name; the few exceptions (`language` → `LanguageISO`) are looked up
         * by inverting [KEY_MAP] so the canonical name comes back.
         */
        fun comicInfoElementsForVariable(variable: String): Set<String> {
            val mapped = KEY_MAP.entries.filter { it.value == variable }.map { it.key }
            val fallback = variable
            // Match is case-insensitive at write time, so we just need the
            // names — not the precise PascalCase casing.
            return (mapped + fallback).toSet()
        }

        /** PascalCase ComicInfo element names keyed by pipeline variable. */
        private val ELEMENT_NAMES = mapOf(
            "title" to "Title",
            "series" to "Series",
            "number" to "Number",
            "writer" to "Writer",
            "penciller" to "Penciller",
            "colorist" to "Colorist",
            "letterer" to "Letterer",
            "inker" to "Inker",
            "publisher" to "Publisher",
            "language" to "LanguageISO",
            "genre" to "Genre",
            "tags" to "Tags",
            "characters" to "Characters",
            "year" to "Year",
            "month" to "Month",
            "day" to "Day",
            "summary" to "Summary",
        )

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
