package com.mangako.app.domain.pipeline

/**
 * Shared logic for assembling a [PipelineInput] from detection-time
 * metadata + the user's edit-detection overrides + removals.
 *
 * Lives in `domain/` because both [com.mangako.app.work.ProcessCbzWorker]
 * (running the real upload) and [com.mangako.app.ui.inbox.InboxViewModel]
 * (simulating titles for the card) need to produce *exactly the same*
 * pipeline input — otherwise the Inbox card lies about what Process will
 * land in LANraragi. Centralising the logic here is the only way to keep
 * those two surfaces in sync.
 */
object PipelineInputBuilder {

    /**
     * Merge detection metadata with user edits and (optionally) seed
     * `%title%` from the filename when nothing else carries it.
     *
     *   merged = (detected − removed) + overrides
     *
     * If the resulting `%title%` is blank AND the user hasn't taken an
     * explicit action on it (override or remove), we fall back to the
     * filename stem so the default pipeline's tag-extraction phases
     * have something to mine.
     */
    fun build(
        originalFilename: String,
        detectedMetadata: Map<String, String>,
        overrides: Map<String, String> = emptyMap(),
        removals: Set<String> = emptySet(),
    ): PipelineInput {
        val merged = (detectedMetadata - removals) + overrides
        val seeded = seedTitleFromFilename(
            metadata = merged,
            filenameStem = originalFilename.removeSuffix(".cbz"),
            removals = removals,
            overrides = overrides,
        )
        return PipelineInput(originalFilename = originalFilename, metadata = seeded)
    }

    /**
     * Default-pipeline tag-extraction phases (event tag, language
     * bracket, trailing tags, bracket-strip) all mine `%title%` for the
     * data they need. When ComicInfo never gave us a title — Mihon
     * source without ComicInfo, malformed XML, etc. — `%title%` stays
     * empty and every downstream rule produces blanks, so the simulated
     * title degrades to the raw filename. Seeding `%title%` with the
     * filename stem makes the same pipeline mine the filename for tags
     * and produce a clean human title even when the archive shipped no
     * ComicInfo.
     *
     * Skip the seed when the user has explicitly removed `title` or set
     * an override (even a blank one) — they've told us they don't want
     * the filename treated as the title, and forcing it back here would
     * silently undo their edit.
     */
    private fun seedTitleFromFilename(
        metadata: Map<String, String>,
        filenameStem: String,
        removals: Set<String>,
        overrides: Map<String, String>,
    ): Map<String, String> {
        if ("title" in removals || "title" in overrides) return metadata
        if (!metadata["title"].isNullOrBlank()) return metadata
        if (filenameStem.isBlank()) return metadata
        return metadata + ("title" to filenameStem)
    }
}
