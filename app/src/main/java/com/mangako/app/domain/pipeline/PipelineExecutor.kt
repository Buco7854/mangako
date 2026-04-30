package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.PipelineConfig

/**
 * Thin compatibility shim over [PipelineEvaluator]. Existing call sites
 * that just need the legacy `Output` shape (working filename + final
 * variables + flat audit list + ComicInfo updates) keep working without
 * a code change.
 *
 * New call sites that want to query a variable's value at a specific
 * step, or inspect the full pre/post context for any rule, should use
 * [PipelineEvaluator.evaluate] directly — the [PipelineEvaluation] it
 * returns is fully introspectable.
 */
class PipelineExecutor(
    /** Wall-clock budget for any single rule, including nested sub-runs. */
    private val ruleTimeoutMs: Long = PipelineEvaluator.DEFAULT_RULE_TIMEOUT_MS,
) {

    private val evaluator = PipelineEvaluator(ruleTimeoutMs = ruleTimeoutMs)

    data class Input(
        val originalFilename: String,
        /** Pre-populated by the CBZ processor from ComicInfo.xml. */
        val metadata: Map<String, String> = emptyMap(),
    )

    data class Output(
        val finalFilename: String,
        val variables: Map<String, String>,
        val steps: List<AuditStep>,
        /**
         * Side-effect map of `<ElementName>` → value collected from any
         * [com.mangako.app.domain.rule.Rule.WriteComicInfo] steps that ran.
         * The worker applies these to the .cbz's ComicInfo.xml after the
         * pipeline finishes; the pipeline itself doesn't have file access.
         */
        val comicInfoUpdates: Map<String, String> = emptyMap(),
    )

    fun run(config: PipelineConfig, input: Input): Output {
        val evaluation = evaluator.evaluate(
            config,
            PipelineInput(originalFilename = input.originalFilename, metadata = input.metadata),
        )
        return Output(
            finalFilename = evaluation.filename,
            variables = evaluation.final.variables,
            steps = evaluation.toAuditSteps(),
            comicInfoUpdates = evaluation.comicInfoUpdates,
        )
    }

    /** Direct access to the queryable [PipelineEvaluation] for callers
     *  that want to read a variable at a specific step or peek into the
     *  full pre/post context. Equivalent to constructing a
     *  [PipelineEvaluator] yourself but reuses this instance's timeout. */
    fun evaluate(config: PipelineConfig, input: Input): PipelineEvaluation =
        evaluator.evaluate(
            config,
            PipelineInput(originalFilename = input.originalFilename, metadata = input.metadata),
        )
}

@Suppress("unused") // convenience for tests / call sites
fun Condition.Op.symbol(): String = when (this) {
    Condition.Op.EQUALS -> "=="
    Condition.Op.NOT_EQUALS -> "!="
    Condition.Op.CONTAINS -> "⊇"
    Condition.Op.NOT_CONTAINS -> "⊉"
    Condition.Op.MATCHES -> "~="
    Condition.Op.IS_EMPTY -> "∅"
    Condition.Op.IS_NOT_EMPTY -> "≠∅"
}
