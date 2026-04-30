package com.mangako.app.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contract tests for the shared input builder. The Inbox simulator and
 * the worker share this code path — these tests are the common floor
 * both surfaces must respect.
 */
class PipelineInputBuilderTest {

    @Test fun `removals are subtracted before overrides`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "x.cbz",
            detectedMetadata = mapOf("title" to "From file", "writer" to "From file"),
            removals = setOf("title"),
            overrides = emptyMap(),
        )
        assertEquals(null, input.metadata["title"])
        assertEquals("From file", input.metadata["writer"])
    }

    @Test fun `overrides win over detected values`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "x.cbz",
            detectedMetadata = mapOf("title" to "From file"),
            overrides = mapOf("title" to "User wins"),
        )
        assertEquals("User wins", input.metadata["title"])
    }

    @Test fun `title is seeded from the filename when missing`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "Real Title.cbz",
            detectedMetadata = emptyMap(),
        )
        assertEquals("Real Title", input.metadata["title"])
    }

    @Test fun `title seed does not override a non-blank detected title`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "Filename Stem.cbz",
            detectedMetadata = mapOf("title" to "From ComicInfo"),
        )
        assertEquals("From ComicInfo", input.metadata["title"])
    }

    @Test fun `title seed is skipped when the user explicitly removed the title`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "Filename.cbz",
            detectedMetadata = emptyMap(),
            removals = setOf("title"),
        )
        // User said "no title" — we do NOT resurrect it from the filename.
        assertEquals(null, input.metadata["title"])
    }

    @Test fun `title seed is skipped when the user has any title override`() {
        val input = PipelineInputBuilder.build(
            originalFilename = "Filename.cbz",
            detectedMetadata = emptyMap(),
            // Even a blank explicit override means "no title from filename".
            overrides = mapOf("title" to ""),
        )
        assertEquals("", input.metadata["title"])
    }
}
