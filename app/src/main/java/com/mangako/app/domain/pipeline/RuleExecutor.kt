package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Rule

/**
 * Strategy for one [Rule] subtype. Each rule type ships its own executor
 * (see `RuleExecutors.kt`) so adding a new rule means writing one file —
 * no editing a giant `when` in the middle of the engine.
 *
 * Implementations are pure: given a context, they produce a [RuleOutcome].
 * The runner ([PipelineEvaluator]) is the only place that touches mutable
 * state (the running list of [EvaluationStep]s), and even that is local
 * to one run.
 */
interface RuleExecutor<R : Rule> {
    fun apply(rule: R, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome
}

/**
 * The result of executing one rule. Either:
 *  - [Applied]: a new context, optionally with substep audit entries
 *    (groups / conditionals expose their inner rules this way).
 *  - [Skipped]: the context is unchanged and the audit log records why.
 */
sealed class RuleOutcome {
    abstract val note: String?
    abstract val durationMs: Long
    abstract val substeps: List<EvaluationStep>

    data class Applied(
        val newContext: PipelineContext,
        override val note: String? = null,
        override val durationMs: Long = 0,
        override val substeps: List<EvaluationStep> = emptyList(),
    ) : RuleOutcome()

    data class Skipped(
        val reason: String,
        override val note: String? = null,
        override val durationMs: Long = 0,
    ) : RuleOutcome() {
        override val substeps: List<EvaluationStep> = emptyList()
    }
}

/**
 * Pass-through environment given to every executor — depth (for audit
 * indenting) and a runner the executor calls when it needs to recurse
 * into nested rules ([Rule.Group], [Rule.ConditionalFormat]).
 *
 * Hiding nested execution behind a callback keeps every executor
 * pure-ish (no direct dependency on the evaluator) and makes nested
 * runs trivially uniform with top-level runs.
 */
class ExecutorEnv(
    val depth: Int,
    val runNested: (rules: List<Rule>, ctx: PipelineContext) -> NestedRunResult,
)

data class NestedRunResult(
    val finalContext: PipelineContext,
    val steps: List<EvaluationStep>,
)
