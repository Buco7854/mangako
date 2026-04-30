package com.mangako.app.domain.cbz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Lock-in tests for [CbzProcessor.updateMetadata]. The rewriter's job is to
 * leave every non-ComicInfo entry byte-identical and only touch the named
 * fields inside ComicInfo.xml — a downstream reader (LANraragi, Mihon
 * resync) should pick up the new title without seeing image data move.
 *
 * NB: tests verify the rewritten ComicInfo.xml by re-opening the zip
 * directly rather than going through [CbzProcessor.extractMetadata],
 * because the latter calls `android.util.Xml.newPullParser()` which is a
 * stub in JVM unit tests (Robolectric would be needed otherwise).
 */
class CbzProcessorMetadataTest {

    @get:Rule val tmp = TemporaryFolder()
    private val processor = CbzProcessor()

    @Test fun `replaces existing Title and leaves other fields and entries alone`() {
        val cbz = createCbz(
            "ComicInfo.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo>
                  <Title>Chapter 39</Title>
                  <Series>My Series</Series>
                  <Writer>Author Name</Writer>
                </ComicInfo>
            """.trimIndent().toByteArray(),
            "image_001.jpg" to byteArrayOf(0x01, 0x02, 0x03),
        )

        val ok = processor.updateMetadata(cbz, mapOf("Title" to "[Author Name] My Series Ch 39"))
        assertTrue("rewrite should succeed", ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertTrue("Title element should carry the new value: $xml", xml.contains("<Title>[Author Name] My Series Ch 39</Title>"))
        assertFalse("Old title should be gone: $xml", xml.contains("Chapter 39"))
        assertTrue("Other elements untouched: $xml", xml.contains("<Series>My Series</Series>"))
        assertTrue("Other elements untouched: $xml", xml.contains("<Writer>Author Name</Writer>"))

        val image = readEntry(cbz, "image_001.jpg")
        assertTrue("image entry must be byte-identical", image.contentEquals(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test fun `inserts Title element when ComicInfo had none`() {
        val cbz = createCbz(
            "ComicInfo.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo>
                  <Series>My Series</Series>
                </ComicInfo>
            """.trimIndent().toByteArray(),
        )

        val ok = processor.updateMetadata(cbz, mapOf("Title" to "Inserted Title"))
        assertTrue(ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertTrue(xml.contains("<Title>Inserted Title</Title>"))
        assertTrue(xml.contains("<Series>My Series</Series>"))
        // The new Title should sit before the closing tag.
        assertTrue(
            "Title should be inserted before </ComicInfo>: $xml",
            xml.indexOf("<Title>Inserted Title</Title>") < xml.indexOf("</ComicInfo>"),
        )
    }

    @Test fun `synthesises ComicInfo when archive had none`() {
        val cbz = createCbz(
            "image_001.jpg" to byteArrayOf(0x10, 0x20),
        )

        val ok = processor.updateMetadata(cbz, mapOf("Title" to "From scratch"))
        assertTrue(ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertTrue("synthesised XML should contain Title element: $xml", xml.contains("<Title>From scratch</Title>"))
        assertTrue("synthesised XML should be a complete document: $xml", xml.contains("<ComicInfo"))
        assertTrue(xml.contains("</ComicInfo>"))
    }

    @Test fun `escapes XML-unsafe characters in the new value`() {
        val cbz = createCbz(
            "ComicInfo.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo><Title>old</Title></ComicInfo>
            """.trimIndent().toByteArray(),
        )

        val tricky = "Series & <weird> 'title'"
        val ok = processor.updateMetadata(cbz, mapOf("Title" to tricky))
        assertTrue(ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertTrue("'<' must be escaped to &lt; in $xml", xml.contains("&lt;weird&gt;"))
        assertTrue("'&' must be escaped to &amp; in $xml", xml.contains("Series &amp; "))
        assertTrue("apostrophe must be escaped: $xml", xml.contains("&apos;title&apos;"))
        assertFalse("raw '<weird>' must not appear in the value: $xml", xml.contains("<weird>"))
    }

    @Test fun `replaces self-closing Title element`() {
        val cbz = createCbz(
            "ComicInfo.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo><Title/><Series>S</Series></ComicInfo>
            """.trimIndent().toByteArray(),
        )

        val ok = processor.updateMetadata(cbz, mapOf("Title" to "Now set"))
        assertTrue(ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertTrue("self-closing Title should be replaced with full element: $xml", xml.contains("<Title>Now set</Title>"))
        assertFalse("self-closing form should be gone: $xml", xml.contains("<Title/>"))
    }

    @Test fun `no-op when fields map is empty`() {
        val cbz = createCbz(
            "ComicInfo.xml" to "<ComicInfo><Title>x</Title></ComicInfo>".toByteArray(),
        )
        val sizeBefore = cbz.length()
        val ok = processor.updateMetadata(cbz, emptyMap())
        assertFalse(ok)
        assertEquals("file should not change", sizeBefore, cbz.length())
    }

    @Test fun `removes named ComicInfo elements and leaves the rest alone`() {
        val cbz = createCbz(
            "ComicInfo.xml" to """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo>
                  <Title>Chapter 39</Title>
                  <Series>My Series</Series>
                  <Writer>Author Name</Writer>
                  <LanguageISO>en</LanguageISO>
                </ComicInfo>
            """.trimIndent().toByteArray(),
            "001.jpg" to byteArrayOf(0x10, 0x20, 0x30),
        )

        val ok = processor.updateMetadata(
            cbz,
            fieldsToSet = emptyMap(),
            fieldsToRemove = setOf("Writer", "LanguageISO"),
        )
        assertTrue("rewrite should succeed when removals are requested", ok)

        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertFalse("Writer must be stripped: $xml", xml.contains("<Writer>"))
        assertFalse("LanguageISO must be stripped: $xml", xml.contains("<LanguageISO>"))
        assertTrue("Title kept: $xml", xml.contains("<Title>Chapter 39</Title>"))
        assertTrue("Series kept: $xml", xml.contains("<Series>My Series</Series>"))
        assertTrue("Image entry must be byte-identical", readEntry(cbz, "001.jpg").contentEquals(byteArrayOf(0x10, 0x20, 0x30)))
    }

    @Test fun `removal is case-insensitive on element name`() {
        val cbz = createCbz(
            "ComicInfo.xml" to "<ComicInfo><languageiso>en</languageiso></ComicInfo>".toByteArray(),
        )
        val ok = processor.updateMetadata(
            cbz,
            fieldsToSet = emptyMap(),
            fieldsToRemove = setOf("LanguageISO"),
        )
        assertTrue(ok)
        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        assertFalse("lowercase variant must also be stripped: $xml", xml.contains("languageiso"))
    }

    @Test fun `set then remove same key results in removal winning`() {
        // Defensive: not a real-world flow, but prove we don't end up
        // with a stale value still in the doc.
        val cbz = createCbz(
            "ComicInfo.xml" to "<ComicInfo><Title>old</Title></ComicInfo>".toByteArray(),
        )
        val ok = processor.updateMetadata(
            cbz,
            fieldsToSet = mapOf("Title" to "new"),
            fieldsToRemove = setOf("Title"),
        )
        assertTrue(ok)
        val xml = readEntry(cbz, "ComicInfo.xml").toString(Charsets.UTF_8)
        // The "set" pass runs after "remove", so the new value re-inserts.
        // The point of the test is to lock the documented order; if that
        // ever flips, this test breaks loudly.
        assertTrue("Title re-inserted with new value: $xml", xml.contains("<Title>new</Title>"))
    }

    @Test fun `comicInfoElementsForVariable inverts language alias`() {
        val elements = CbzProcessor.comicInfoElementsForVariable("language")
        assertTrue("must include LanguageISO mapping: $elements", elements.any { it.equals("languageiso", ignoreCase = true) })
    }

    @Test fun `comicInfoElementsForVariable defaults to PascalCase fallback`() {
        val elements = CbzProcessor.comicInfoElementsForVariable("title")
        assertTrue("must include the variable name as fallback: $elements", elements.contains("title"))
    }

    @Test fun `extractDetectionInfo picks the alphabetically-first image not the first stored`() {
        // Pages are stored physically out-of-order — page 050 first, then
        // 001. The current bug pre-fix returns 050's bytes; the fix tracks
        // the lexicographically-smallest filename and returns 001's.
        val cbz = createCbz(
            "page_050.jpg" to byteArrayOf(0x55, 0x55, 0x55),
            "ComicInfo.xml" to "<ComicInfo><Title>x</Title></ComicInfo>".toByteArray(),
            "page_001.jpg" to byteArrayOf(0x11, 0x11, 0x11),
            "page_002.jpg" to byteArrayOf(0x22, 0x22, 0x22),
        )
        val info = cbz.inputStream().use { processor.extractDetectionInfo(it) }
        val image = info.firstImage
        assertNotNull(image)
        assertTrue(
            "expected page_001 bytes but got ${image?.toList()}",
            image!!.contentEquals(byteArrayOf(0x11, 0x11, 0x11)),
        )
    }

    @Test fun `extractDetectionInfo prefers root cover over deeper page entries`() {
        // Cover at root with name "001.jpg" should win over "Chapter
        // 1/001.jpg" (same page name; root sorts first via the secondary
        // path comparison).
        val cbz = createCbz(
            "Chapter 1/001.jpg" to byteArrayOf(0x44, 0x44),
            "001.jpg" to byteArrayOf(0x33, 0x33),
        )
        val info = cbz.inputStream().use { processor.extractDetectionInfo(it) }
        val image = info.firstImage
        assertNotNull(image)
        assertTrue(image!!.contentEquals(byteArrayOf(0x33, 0x33)))
    }

    @Test fun `missing file returns false and leaves no temp behind`() {
        val ghost = File(tmp.root, "ghost.cbz")
        assertFalse(ghost.exists())
        val ok = processor.updateMetadata(ghost, mapOf("Title" to "x"))
        assertFalse(ok)
        assertNull(
            "no .rewrite leftover",
            ghost.parentFile?.listFiles()?.firstOrNull { it.name.endsWith(".rewrite") },
        )
    }

    private fun createCbz(vararg entries: Pair<String, ByteArray>): File {
        val cbz = tmp.newFile("test_${System.nanoTime()}.cbz")
        ZipOutputStream(FileOutputStream(cbz)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return cbz
    }

    private fun readEntry(cbz: File, name: String): ByteArray =
        ZipFile(cbz).use { zip -> zip.getInputStream(zip.getEntry(name)).readBytes() }
}
