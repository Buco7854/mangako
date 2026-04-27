package com.mangako.app.domain.rule

import com.mangako.app.domain.pipeline.PipelineExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization round-trip + schema tests. Exercises every Rule subclass via
 * the LANraragi default template, so adding a new rule type without teaching
 * kotlinx-serialization about it will fail here.
 */
class PipelineJsonTest {

    @Test fun `default template round-trips through export then import`() {
        val original = DefaultTemplate.lanraragiStandard()
        val json = RuleJson.export(original)
        val decoded = RuleJson.import(json).getOrThrow()
        assertEquals(original.name, decoded.name)
        assertEquals(original.rules.size, decoded.rules.size)
        original.rules.zip(decoded.rules).forEach { (a, b) ->
            assertEquals(a::class, b::class)
            assertEquals(a.id, b.id)
        }
    }

    @Test fun `import rejects a schema version newer than this build supports`() {
        val fromFuture = """{"schema": 999, "name": "x", "rules": []}"""
        val result = RuleJson.import(fromFuture)
        assertTrue(result.isFailure)
    }

    /**
     * Smoke tests pinning the LANraragi standard pipeline against the
     * representative behaviours the original lrr-preprocess.sh / mihon.sh
     * scripts performed. These are the contract: if a refactor breaks one
     * of these the user-visible rename behaviour has changed.
     */
    @Test fun `lanraragi standard - emoji flag becomes language tag`() {
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "[Artist] Title 🇯🇵.cbz",
                metadata = mapOf("writer" to "Artist", "language" to "Japanese"),
            ),
        )
        // 🇯🇵 should be replaced with [Japanese] and the file should still be a .cbz.
        assertTrue(
            "Expected '[Japanese]' in '${pipeline.finalFilename}'",
            pipeline.finalFilename.contains("[Japanese]"),
        )
        assertTrue(pipeline.finalFilename.endsWith(".cbz"))
    }

    @Test fun `lanraragi standard - missing writer prefix gets prepended`() {
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "Untagged file.cbz",
                metadata = mapOf("writer" to "Someone", "language" to "English"),
            ),
        )
        // Filename had no leading [bracket], so the writer prefix should be inserted.
        assertTrue(
            "Expected '[Someone]' in '${pipeline.finalFilename}'",
            pipeline.finalFilename.contains("[Someone]"),
        )
    }

    @Test fun `lanraragi standard - rebuilds filename from ComicInfo, not detection`() {
        // The pipeline now extracts everything it needs from ComicInfo
        // and assembles the filename from scratch in one template
        // step, so whatever bracket tags happened to be in the
        // detected filename are irrelevant — the upload name is
        // derived from %writer%, %title%, %language% (et al). If the
        // user wants a different writer they fix it via the
        // edit-detection sheet, not by trusting the original filename.
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "[OldArtist] Whatever.cbz",
                metadata = mapOf(
                    "writer" to "Other",
                    "title" to "Real Title",
                    "language" to "English",
                ),
            ),
        )
        assertEquals("[Other] Real Title [English].cbz", pipeline.finalFilename)
    }

    @Test fun `lanraragi standard - sanitises forbidden characters in metadata`() {
        // The build template is "[%writer%] %title% [%language%]…" so
        // the only way bad chars end up in the filename is via metadata
        // (e.g. a downloader stuffing a colon into the series title).
        // The sanitise step must still strip them.
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "x.cbz",
                metadata = mapOf(
                    "writer" to "Artist",
                    "title" to "Title*?<>|",
                    "language" to "English",
                ),
            ),
        )
        listOf("*", "?", "<", ">", "|").forEach { c ->
            assertTrue(
                "Sanitiser should remove '$c' from '${pipeline.finalFilename}'",
                !pipeline.finalFilename.contains(c),
            )
        }
    }

    @Test fun `import tolerates unknown fields`() {
        val withExtra = """
            {"schema":1,"name":"x","rules":[
              {"type":"clean_whitespace","id":"a","trim":true,"future_field":"ignored"}
            ]}
        """.trimIndent()
        val config = RuleJson.import(withExtra).getOrThrow()
        assertEquals(1, config.rules.size)
    }
}
