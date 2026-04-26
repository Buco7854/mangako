package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.Rule

/**
 * Drives a [PipelineConfig] over an input filename, threading two pieces of
 * state through every rule:
 *   - the working filename (string being mutated)
 *   - a map of named variables (populated by [Rule.ExtractXmlMetadata] and
 *     [Rule.ExtractRegex], consumed by other rules via token interpolation:
 *     "%title%", "%series%", etc.)
 *
 * Every rule invocation produces an [AuditStep]; the caller can replay them to
 * reconstruct exactly what happened.
 *
 * Each rule is executed under a [ruleTimeoutMs] guard to stop a catastrophic
 * user-supplied regex (ReDoS) from stalling the worker indefinitely.
 */
class PipelineExecutor(
    /** Wall-clock budget for any single rule, including nested Conditional sub-runs. */
    private val ruleTimeoutMs: Long = DEFAULT_RULE_TIMEOUT_MS,
) {

    data class Input(
        val originalFilename: String,
        /** Pre-populated by the CBZ processor from ComicInfo.xml. */
        val metadata: Map<String, String> = emptyMap(),
    )

    data class Output(
        val finalFilename: String,
        val variables: Map<String, String>,
        val steps: List<AuditStep>,
    )

    fun run(config: PipelineConfig, input: Input): Output =
        runAtDepth(config, input, depth = 0, baseIndex = 0)

    private fun runAtDepth(config: PipelineConfig, input: Input, depth: Int, baseIndex: Int): Output {
        val steps = mutableListOf<AuditStep>()
        // "__filename__" is a reserved variable — rules can inspect it in conditions.
        val vars = LinkedHashMap<String, String>(input.metadata).apply {
            put("__filename__", input.originalFilename)
        }
        var current = input.originalFilename
        var idx = baseIndex
        for (rule in config.rules) {
            val res = step(rule, current, vars, depth)
            steps += res.asAudit(idx++, depth)
            if (!res.skipped) {
                current = res.after
                vars["__filename__"] = current
                vars += res.variableUpdates
            }
            // Conditional rules flatten their own sub-steps into the trail,
            // tagged with depth+1 so the UI can indent them.
            if (res.substeps.isNotEmpty()) {
                steps += res.substeps
                idx += res.substeps.size
            }
        }
        return Output(finalFilename = current, variables = vars, steps = steps)
    }

    private fun step(rule: Rule, input: String, vars: Map<String, String>, depth: Int): StepResult {
        if (!rule.enabled) {
            return StepResult.skipped(rule, input, "Rule disabled")
        }
        val started = System.nanoTime()
        return try {
            withTimeout(ruleTimeoutMs, rule, input) {
                when (rule) {
                    is Rule.ExtractXmlMetadata -> StepResult(
                        rule = rule,
                        before = input,
                        after = input,
                        variableUpdates = emptyMap(),
                        note = "Loaded ${vars.count { !it.key.startsWith("__") }} variable(s)",
                        durationMs = msSince(started),
                    )

                    is Rule.ExtractRegex -> {
                        val already = vars[rule.target].orEmpty()
                        if (rule.onlyIfEmpty && already.isNotEmpty()) {
                            StepResult.skipped(rule, input, "%${rule.target}% already set")
                        } else {
                            val src = vars[rule.source].orEmpty()
                            val captured = if (rule.pattern.isBlank()) {
                                src
                            } else {
                                val opts = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                                val match = Regex(rule.pattern, opts).find(src)
                                // MatchGroupCollection doesn't expose getOrNull — guard by index
                                // to survive patterns with no capture groups or a group arg out of range.
                                match?.groupValues?.getOrNull(rule.group).orEmpty()
                            }
                            val resolved = captured.ifBlank { interpolate(rule.defaultValue, vars) }
                            if (resolved.isBlank()) {
                                StepResult.skipped(rule, input, "No match and no default")
                            } else {
                                StepResult(
                                    rule = rule,
                                    before = input,
                                    after = input,
                                    variableUpdates = mapOf(rule.target to resolved),
                                    note = "%${rule.target}% = \"${resolved.take(32)}\"",
                                    durationMs = msSince(started),
                                )
                            }
                        }
                    }

                    is Rule.RegexReplace -> {
                        val opts = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        val regex = Regex(rule.pattern, opts)
                        val replacement = interpolate(rule.replacement, vars, escapeReplacement = true)
                        val out = regex.replace(input, replacement)
                        StepResult(rule, input, out, emptyMap(), durationMs = msSince(started))
                    }

                    is Rule.RegexReplaceMany -> {
                        val opts = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                        var working = input
                        var hits = 0
                        for (r in rule.replacements) {
                            if (r.pattern.isEmpty()) continue
                            val regex = Regex(r.pattern, opts)
                            val replacement = interpolate(r.replacement, vars, escapeReplacement = true)
                            val next = regex.replace(working, replacement)
                            if (next != working) hits++
                            working = next
                        }
                        StepResult(
                            rule = rule,
                            before = input,
                            after = working,
                            variableUpdates = emptyMap(),
                            note = if (hits == 0) "no replacement matched" else "$hits / ${rule.replacements.size} matched",
                            durationMs = msSince(started),
                        )
                    }

                    is Rule.StringAppend ->
                        StepResult(rule, input, input + interpolate(rule.text, vars, false), emptyMap(), durationMs = msSince(started))

                    is Rule.StringPrepend ->
                        StepResult(rule, input, interpolate(rule.text, vars, false) + input, emptyMap(), durationMs = msSince(started))

                    is Rule.TagRelocator -> {
                        val regex = Regex(rule.pattern)
                        val match = regex.find(input)
                        if (match == null) {
                            StepResult.skipped(rule, input, "No match")
                        } else {
                            val groupText = match.groups[rule.group]?.value ?: match.value
                            // Collapse the whitespace gap left behind by the excised tag so
                            // "[Artist] Title (C96) [English]" doesn't end up with a double
                            // space between "Title" and "[English]".
                            val removed = input.removeRange(match.range)
                                .replace(Regex("\\s{2,}"), " ")
                                .trim()
                            val reassembled = when (rule.position) {
                                Rule.TagRelocator.Position.FRONT -> groupText + rule.separator + removed
                                Rule.TagRelocator.Position.BACK -> removed + rule.separator + groupText
                            }
                            StepResult(rule, input, reassembled, emptyMap(), durationMs = msSince(started))
                        }
                    }

                    is Rule.ConditionalFormat -> {
                        val branchTaken = rule.condition.evaluate(vars + mapOf("__filename__" to input))
                        val branch = if (branchTaken) rule.thenRules else rule.elseRules
                        // Recurse: run the branch's rules inline and capture their sub-audit
                        // tagged at depth + 1 so the history UI can indent them.
                        val subOut = runAtDepth(
                            PipelineConfig(rules = branch),
                            Input(input, vars),
                            depth = depth + 1,
                            baseIndex = 0,
                        )
                        StepResult(
                            rule = rule,
                            before = input,
                            after = subOut.finalFilename,
                            variableUpdates = subOut.variables.filterKeys { it !in vars || vars[it] != subOut.variables[it] },
                            note = "Branch: ${if (branchTaken) "THEN" else "ELSE"} (${branch.size} rule${if (branch.size == 1) "" else "s"})",
                            substeps = subOut.steps,
                            durationMs = msSince(started),
                        )
                    }

                    is Rule.CleanWhitespace -> {
                        val collapsed = input.replace(Regex("\\s{2,}"), " ")
                        val out = if (rule.trim) collapsed.trim() else collapsed
                        StepResult(rule, input, out, emptyMap(), durationMs = msSince(started))
                    }
                }
            }
        } catch (t: Throwable) {
            // A misconfigured regex must not nuke the whole pipeline.
            StepResult.skipped(rule, input, "Error: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Run [block] on a scratch thread and abandon it if it doesn't return within
     * [budgetMs]. Regex evaluation in Kotlin/JDK is a CPU-bound call with no
     * cooperative cancellation, so we lean on interrupt + return-abandon.
     *
     * On timeout we log a [StepResult.skipped] with a clear reason; the pipeline
     * continues with unchanged state.
     */
    private fun withTimeout(budgetMs: Long, rule: Rule, input: String, block: () -> StepResult): StepResult {
        var result: StepResult? = null
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
            return StepResult.skipped(rule, input, "Rule exceeded ${budgetMs}ms budget (possible ReDoS)")
        }
        thrown?.let { throw it }
        return result ?: StepResult.skipped(rule, input, "Rule produced no result")
    }

    private fun msSince(nanos: Long) = (System.nanoTime() - nanos) / 1_000_000

    /**
     * Expand %var% tokens against the variable map. Unknown tokens are left alone.
     *
     * @param escapeReplacement when true, escapes `$` and `\` in substituted *values*
     *   only — the literal template keeps its regex-replacement semantics, so authors
     *   can still write `Ch %number% $1`.
     */
    private fun interpolate(template: String, vars: Map<String, String>, escapeReplacement: Boolean = false): String =
        TOKEN.replace(template) { m ->
            val v = vars[m.groupValues[1]] ?: return@replace m.value
            if (escapeReplacement) v.replace("\\", "\\\\").replace("$", "\\$") else v
        }

    private data class StepResult(
        val rule: Rule,
        val before: String,
        val after: String,
        val variableUpdates: Map<String, String>,
        val skipped: Boolean = false,
        val skippedReason: String? = null,
        val note: String? = null,
        val substeps: List<AuditStep> = emptyList(),
        val durationMs: Long = 0,
    ) {
        fun asAudit(index: Int, depth: Int) = AuditStep(
            index = index,
            ruleId = rule.id,
            ruleType = rule::class.simpleName.orEmpty(),
            ruleLabel = rule.displayName(),
            before = before,
            after = after,
            skipped = skipped,
            skippedReason = skippedReason,
            note = note,
            variablesAfter = variableUpdates,
            durationMs = durationMs,
            depth = depth,
        )

        companion object {
            fun skipped(rule: Rule, input: String, reason: String) =
                StepResult(rule, input, input, emptyMap(), skipped = true, skippedReason = reason)
        }
    }

    companion object {
        const val DEFAULT_RULE_TIMEOUT_MS = 1_000L
        private val TOKEN = Regex("%([a-zA-Z_][a-zA-Z0-9_]*)%")
    }
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
