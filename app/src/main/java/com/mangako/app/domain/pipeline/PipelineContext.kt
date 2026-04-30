package com.mangako.app.domain.pipeline

/**
 * Immutable snapshot of pipeline state at one moment. Every rule produces a
 * new context instead of mutating an existing one; the executor threads these
 * through the rule list and records each transition in [EvaluationStep].
 *
 * The reserved variables `__filename__` and `__filename_stem__` always
 * mirror [filename] minus a trailing `.cbz`, so user-authored conditions and
 * templates can interpolate the working filename without the executor
 * having to special-case it everywhere.
 */
data class PipelineContext(
    val filename: String,
    val variables: Map<String, String>,
    /**
     * `<ElementName>` → value writes the worker should apply to the
     * archive's ComicInfo.xml after the pipeline finishes. Accumulated
     * across the run; later writes for the same key win, matching how
     * a user reading the rule list top-down would expect.
     */
    val comicInfoUpdates: Map<String, String> = emptyMap(),
) {
    operator fun get(name: String): String? = variables[name]

    /** Replace the working filename, keeping the reserved %__filename__% / %__filename_stem__% in sync. */
    fun withFilename(newFilename: String): PipelineContext {
        val updated = LinkedHashMap(variables).apply {
            put(RESERVED_FILENAME, newFilename)
            put(RESERVED_FILENAME_STEM, newFilename.removeSuffix(".cbz"))
        }
        return copy(filename = newFilename, variables = updated)
    }

    /** Set or merge variables. If the special `__filename__` is among
     *  [updates], the working filename is rewritten to match. */
    fun withVariables(updates: Map<String, String>): PipelineContext {
        if (updates.isEmpty()) return this
        val merged = LinkedHashMap(variables).apply { putAll(updates) }
        val newFilename = updates[RESERVED_FILENAME] ?: filename
        val mirrored = if (newFilename != filename) {
            merged.also {
                it[RESERVED_FILENAME] = newFilename
                it[RESERVED_FILENAME_STEM] = newFilename.removeSuffix(".cbz")
            }
        } else merged
        return copy(filename = newFilename, variables = mirrored)
    }

    /** Queue ComicInfo.xml writes — accumulates across the run. */
    fun withComicInfoUpdates(updates: Map<String, String>): PipelineContext {
        if (updates.isEmpty()) return this
        return copy(comicInfoUpdates = comicInfoUpdates + updates)
    }

    companion object {
        const val RESERVED_FILENAME = "__filename__"
        const val RESERVED_FILENAME_STEM = "__filename_stem__"

        /** Initial context for an [PipelineInput]. */
        fun seed(input: PipelineInput): PipelineContext {
            val vars = LinkedHashMap<String, String>(input.metadata).apply {
                put(RESERVED_FILENAME, input.originalFilename)
                put(RESERVED_FILENAME_STEM, input.originalFilename.removeSuffix(".cbz"))
            }
            return PipelineContext(filename = input.originalFilename, variables = vars)
        }
    }
}

/**
 * Inputs the user / worker hands to the pipeline. [originalFilename] is the
 * raw .cbz name as it was found on disk; [metadata] is the merged
 * ComicInfo + user-overrides map produced by [PipelineInputBuilder].
 */
data class PipelineInput(
    val originalFilename: String,
    val metadata: Map<String, String> = emptyMap(),
)
