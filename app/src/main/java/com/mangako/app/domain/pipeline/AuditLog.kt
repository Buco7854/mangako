package com.mangako.app.domain.pipeline

import kotlinx.serialization.Serializable

/**
 * A single mutation recorded during a pipeline run. The builder records one of
 * these per rule so the History screen can show exactly what each step did.
 */
@Serializable
data class AuditStep(
    val index: Int,
    val ruleId: String,
    val ruleType: String,
    val ruleLabel: String,
    val before: String,
    val after: String,
    val skipped: Boolean = false,
    val skippedReason: String? = null,
    val note: String? = null,
    val variablesBefore: Map<String, String> = emptyMap(),
    val variablesAfter: Map<String, String> = emptyMap(),
    val durationMs: Long = 0,
    /**
     * Depth in the rule tree. 0 = top-level pipeline step; 1+ = nested inside a
     * [Rule.ConditionalFormat] branch. Used by the History UI to indent sub-steps
     * so the nesting structure is visible instead of a flat list.
     */
    val depth: Int = 0,
) {
    val changed: Boolean get() = !skipped && before != after
}

@Serializable
data class AuditTrail(
    val sourceFile: String,
    val startedAt: Long,
    val finishedAt: Long,
    val steps: List<AuditStep>,
    val finalName: String,
    val uploadStatus: UploadStatus,
    /**
     * Cumulative ComicInfo.xml writes the pipeline produced — the
     * `<Element>` → value pairs the worker applied to the .cbz after
     * the pipeline finished. Empty when the pipeline doesn't include
     * a [com.mangako.app.domain.rule.Rule.WriteComicInfo] step.
     *
     * Defaults to [emptyMap] so audit trails serialised by older
     * builds (which didn't carry this field) round-trip cleanly under
     * the JSON `ignoreUnknownKeys`/`encodeDefaults` settings.
     */
    val comicInfoUpdates: Map<String, String> = emptyMap(),
)

@Serializable
data class UploadStatus(
    val success: Boolean,
    val httpCode: Int? = null,
    val message: String? = null,
    val deletedSource: Boolean = false,
)
