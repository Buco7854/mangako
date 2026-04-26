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

    @Test fun `lanraragi standard - leaves filename with existing artist tag alone`() {
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "[Artist] Title.cbz",
                metadata = mapOf("writer" to "Other", "language" to "English"),
            ),
        )
        // No second [Other] should be prepended — user already has a leading tag.
        assertTrue(
            "Did not expect a second '[Other]' prefix in '${pipeline.finalFilename}'",
            !pipeline.finalFilename.startsWith("[Other]"),
        )
    }

    @Test fun `lanraragi standard - sanitises forbidden characters`() {
        val pipeline = PipelineExecutor().run(
            DefaultTemplate.lanraragiStandard(),
            PipelineExecutor.Input(
                originalFilename = "[Artist] Title*?<>|.cbz",
                metadata = mapOf("writer" to "Artist", "language" to "English"),
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
