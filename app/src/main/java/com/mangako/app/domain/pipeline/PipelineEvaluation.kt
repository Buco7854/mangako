package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Rule

/**
 * One row in the audit log produced by an evaluation run. Carries the
 * full before/after [PipelineContext] (not just the filename), so callers
 * can introspect any variable's value at any point in the pipeline
 * without re-running it. Nested rules ([Rule.Group], [Rule.ConditionalFormat])
 * surface their inner rules through [children].
 */
data class EvaluationStep(
    val index: Int,
    val rule: Rule,
    val before: PipelineContext,
    val after: PipelineContext,
    val skipped: Boolean = false,
    val skippedReason: String? = null,
    val note: String? = null,
    val durationMs: Long = 0,
    /** Nesting level — 0 = top-level rule; 1+ inside a group / branch. */
    val depth: Int = 0,
    val children: List<EvaluationStep> = emptyList(),
) {
    val changed: Boolean get() = !skipped && before.filename != after.filename
}

/**
 * Result of running a pipeline — fully introspectable.
 *
 *   evaluation["title"]                  // final title
 *   evaluation.variableAt("title", 5)    // value of %title% AFTER step 5
 *   evaluation.contextAt(5)              // full snapshot after step 5
 *   evaluation.flatSteps()               // legacy flat audit list
 *
 * The point of this type: callers that used to mash the rules through
 * the engine just to read out one variable now have a clean, lazy API.
 * No hidden state, no surprises.
 */
class PipelineEvaluation(
    val input: PipelineInput,
    val initial: PipelineContext,
    val final: PipelineContext,
    /** Top-level steps in the order they ran. Use [flatSteps] for a
     *  fully-flattened list including nested children. */
    val steps: List<EvaluationStep>,
) {
    /** Final value of [name] after the pipeline finished, or null if the
     *  variable was never set. */
    operator fun get(name: String): String? = final.variables[name]

    /** Final filename — the upload name the worker writes. */
    val filename: String get() = final.filename

    /** Side-effect map for the worker to apply to ComicInfo.xml. */
    val comicInfoUpdates: Map<String, String> get() = final.comicInfoUpdates

    /**
     * Value of [name] AFTER step [stepIndex] ran (in the flat order).
     * Returns null when the variable wasn't set at that point.
     *
     * Negative indices read the initial context (before the pipeline
     * started); indices past the end read the final context.
     */
    fun variableAt(name: String, stepIndex: Int): String? = contextAt(stepIndex).variables[name]

    /** Full snapshot of the pipeline state after step [stepIndex] ran. */
    fun contextAt(stepIndex: Int): PipelineContext {
        if (stepIndex < 0) return initial
        val flat = flatSteps()
        return when {
            flat.isEmpty() -> final
            stepIndex >= flat.size -> final
            else -> flat[stepIndex].after
        }
    }

    /**
     * Flattened audit trail — every step (including nested) in execution
     * order. Cached because the History UI scrolls through this.
     */
    fun flatSteps(): List<EvaluationStep> {
        if (cachedFlat != null) return cachedFlat!!
        val out = mutableListOf<EvaluationStep>()
        fun visit(s: EvaluationStep) {
            out += s
            s.children.forEach(::visit)
        }
        steps.forEach(::visit)
        return out.also { cachedFlat = it }
    }

    /** Translate the evaluation into the legacy [AuditStep] list expected
     *  by the History database / UI. Re-indexes monotonically across the
     *  flat tree so the History screen keeps a stable ordering. */
    fun toAuditSteps(): List<AuditStep> = flatSteps().mapIndexed { i, s ->
        AuditStep(
            index = i,
            ruleId = s.rule.id,
            ruleType = s.rule::class.simpleName.orEmpty(),
            ruleLabel = s.rule.displayName(),
            before = s.before.filename,
            after = s.after.filename,
            skipped = s.skipped,
            skippedReason = s.skippedReason,
            note = s.note,
            variablesAfter = variableDelta(s.before.variables, s.after.variables),
            durationMs = s.durationMs,
            depth = s.depth,
        )
    }

    @Volatile private var cachedFlat: List<EvaluationStep>? = null

    private fun variableDelta(before: Map<String, String>, after: Map<String, String>): Map<String, String> {
        if (before === after) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in after) {
            if (before[k] != v) out[k] = v
        }
        return out
    }
}
