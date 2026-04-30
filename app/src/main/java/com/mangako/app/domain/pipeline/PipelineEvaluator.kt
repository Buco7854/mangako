package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.Rule

/**
 * The new pipeline driver. Pure-functional core: rules consume an
 * immutable [PipelineContext] and produce a new one via their per-type
 * [RuleExecutor]. Every transition is captured in an [EvaluationStep],
 * giving callers a fully-introspectable [PipelineEvaluation] without
 * having to re-run the pipeline to ask "what was %title% at step 5?".
 *
 * Each rule runs under a [ruleTimeoutMs] guard so a misconfigured user
 * regex (ReDoS) can't stall the worker indefinitely.
 *
 * Stateless and reusable — one evaluator can drive any number of runs.
 */
class PipelineEvaluator(
    private val ruleTimeoutMs: Long = DEFAULT_RULE_TIMEOUT_MS,
    private val registry: Map<Class<out Rule>, RuleExecutor<*>> = DEFAULT_RULE_EXECUTORS,
) {

    /** Run [config] over [input] and return the queryable evaluation. */
    fun evaluate(config: PipelineConfig, input: PipelineInput): PipelineEvaluation {
        val initial = PipelineContext.seed(input)
        val (final, steps) = run(config.rules, initial, depth = 0)
        return PipelineEvaluation(input, initial, final, steps)
    }

    private fun run(
        rules: List<Rule>,
        seed: PipelineContext,
        depth: Int,
    ): Pair<PipelineContext, List<EvaluationStep>> {
        var ctx = seed
        val steps = ArrayList<EvaluationStep>(rules.size)
        for ((i, rule) in rules.withIndex()) {
            val before = ctx
            val started = System.nanoTime()
            val outcome = step(rule, ctx, depth)
            val durationMs = (System.nanoTime() - started) / 1_000_000
            val after = (outcome as? RuleOutcome.Applied)?.newContext ?: ctx
            steps += EvaluationStep(
                index = i,
                rule = rule,
                before = before,
                after = after,
                skipped = outcome is RuleOutcome.Skipped,
                skippedReason = (outcome as? RuleOutcome.Skipped)?.reason,
                note = outcome.note,
                durationMs = durationMs,
                depth = depth,
                children = outcome.substeps,
            )
            ctx = after
        }
        return ctx to steps
    }

    private fun step(rule: Rule, ctx: PipelineContext, depth: Int): RuleOutcome {
        if (!rule.enabled) return RuleOutcome.Skipped("Rule disabled")
        return try {
            withTimeout(ruleTimeoutMs, rule) { dispatch(rule, ctx, depth) }
        } catch (t: Throwable) {
            // A misconfigured regex must not nuke the whole pipeline.
            RuleOutcome.Skipped("Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dispatch(rule: Rule, ctx: PipelineContext, depth: Int): RuleOutcome {
        val executor = registry[rule::class.java]
            ?: return RuleOutcome.Skipped("No executor registered for ${rule::class.simpleName}")
        val env = ExecutorEnv(
            depth = depth,
            runNested = { rules, c ->
                val (finalCtx, nestedSteps) = run(rules, c, depth + 1)
                NestedRunResult(finalCtx, nestedSteps)
            },
        )
        return (executor as RuleExecutor<Rule>).apply(rule, ctx, env)
    }

    /**
     * Run [block] on a scratch thread and abandon it if it doesn't return within
     * [budgetMs]. Regex evaluation in Kotlin/JDK is a CPU-bound call with no
     * cooperative cancellation, so we lean on interrupt + return-abandon.
     *
     * On timeout we record [RuleOutcome.Skipped] with a clear reason; the
     * pipeline continues with unchanged state.
     */
    private fun withTimeout(budgetMs: Long, rule: Rule, block: () -> RuleOutcome): RuleOutcome {
        var result: RuleOutcome? = null
        var thrown: Throwable? = null
        val worker = Thread(
            {
                try {
                    result = block()
                } catch (t: Throwable) {
                    thrown = t
                }
            },
            "rule-${rule.id.take(8)}",
        ).apply { isDaemon = true }
        worker.start()
        worker.join(budgetMs)
        if (worker.isAlive) {
            worker.interrupt()
            return RuleOutcome.Skipped("Rule exceeded ${budgetMs}ms budget (possible ReDoS)")
        }
        thrown?.let { throw it }
        return result ?: RuleOutcome.Skipped("Rule produced no result")
    }

    companion object {
        const val DEFAULT_RULE_TIMEOUT_MS = 1_000L
    }
}
