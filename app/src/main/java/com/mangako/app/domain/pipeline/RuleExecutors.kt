package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Rule

/**
 * Per-rule [RuleExecutor] implementations. One file so the conversion
 * between rule types is easy to scan; each executor is small and pure
 * enough that splitting them across files would be more noise than help.
 *
 * The default registry [DEFAULT_RULE_EXECUTORS] is consumed by
 * [PipelineEvaluator]. Adding a new [Rule] subclass means writing one
 * executor here and adding one entry to the map — there is no central
 * `when` to update.
 */

private val TOKEN = Regex("%([a-zA-Z_][a-zA-Z0-9_]*)%")

/**
 * Expand `%var%` tokens against [vars]. Unknown tokens are left literal —
 * users see "%missing%" in the output instead of an empty hole, which
 * makes pipeline mistakes obvious.
 *
 * @param escapeReplacement when true, escapes `$` and `\` in substituted
 *   *values* only. The literal template keeps regex-replacement semantics
 *   so users can still write `Ch %number% $1` and expect `$1` to refer to
 *   the regex capture group rather than dollar-escaping the previous var.
 */
internal fun interpolate(template: String, vars: Map<String, String>, escapeReplacement: Boolean = false): String =
    TOKEN.replace(template) { m ->
        val v = vars[m.groupValues[1]] ?: return@replace m.value
        if (escapeReplacement) v.replace("\\", "\\\\").replace("$", "\\$") else v
    }

internal object ExtractXmlMetadataExecutor : RuleExecutor<Rule.ExtractXmlMetadata> {
    override fun apply(rule: Rule.ExtractXmlMetadata, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        // Variables from ComicInfo.xml are seeded into the initial context
        // by the input builder, so this rule is a no-op at execution time —
        // it exists in the rule list for two reasons: (1) it makes the
        // ExtractXml step visible in the History audit, (2) the editor
        // surfaces the user-configurable mapping table on it.
        val nonReserved = ctx.variables.count { !it.key.startsWith("__") }
        return RuleOutcome.Applied(
            newContext = ctx,
            note = "Loaded $nonReserved variable(s)",
        )
    }
}

internal object ExtractRegexExecutor : RuleExecutor<Rule.ExtractRegex> {
    override fun apply(rule: Rule.ExtractRegex, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val already = ctx.variables[rule.target].orEmpty()
        if (rule.onlyIfEmpty && already.isNotEmpty()) {
            return RuleOutcome.Skipped("%${rule.target}% already set")
        }
        val src = ctx.variables[rule.source].orEmpty()
        val captured = if (rule.pattern.isBlank()) {
            src
        } else {
            val opts = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            val match = Regex(rule.pattern, opts).find(src)
            // MatchGroupCollection doesn't expose getOrNull — guard the
            // index so a pattern with no capture groups (or a too-large
            // group arg) fails as "no match" rather than crashing.
            match?.groupValues?.getOrNull(rule.group).orEmpty()
        }
        val resolved = captured.ifBlank { interpolate(rule.defaultValue, ctx.variables) }
        if (resolved.isBlank()) return RuleOutcome.Skipped("No match and no default")
        return RuleOutcome.Applied(
            newContext = ctx.withVariables(mapOf(rule.target to resolved)),
            note = "%${rule.target}% = \"$resolved\"",
        )
    }
}

internal object SetVariableExecutor : RuleExecutor<Rule.SetVariable> {
    override fun apply(rule: Rule.SetVariable, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        if (rule.target.isBlank()) return RuleOutcome.Skipped("No target variable set")
        val resolved = interpolate(rule.value, ctx.variables, escapeReplacement = false)
        // Setting %__filename__% rewrites the working filename — that's
        // how the default template's "Build filename" step composes the
        // final name from a single template like
        // "[%writer%] %title% [%language%].cbz".
        return RuleOutcome.Applied(
            newContext = ctx.withVariables(mapOf(rule.target to resolved)),
            note = "%${rule.target}% = \"$resolved\"",
        )
    }
}

internal object RegexReplaceExecutor : RuleExecutor<Rule.RegexReplace> {
    override fun apply(rule: Rule.RegexReplace, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val opts = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val regex = Regex(rule.pattern, opts)
        val replacement = interpolate(rule.replacement, ctx.variables, escapeReplacement = true)
        val out = regex.replace(ctx.filename, replacement)
        return RuleOutcome.Applied(newContext = ctx.withFilename(out))
    }
}

internal object StringAppendExecutor : RuleExecutor<Rule.StringAppend> {
    override fun apply(rule: Rule.StringAppend, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome =
        RuleOutcome.Applied(ctx.withFilename(ctx.filename + interpolate(rule.text, ctx.variables, false)))
}

internal object StringPrependExecutor : RuleExecutor<Rule.StringPrepend> {
    override fun apply(rule: Rule.StringPrepend, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome =
        RuleOutcome.Applied(ctx.withFilename(interpolate(rule.text, ctx.variables, false) + ctx.filename))
}

internal object TagRelocatorExecutor : RuleExecutor<Rule.TagRelocator> {
    override fun apply(rule: Rule.TagRelocator, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val match = Regex(rule.pattern).find(ctx.filename)
            ?: return RuleOutcome.Skipped("No match")
        val groupText = match.groups[rule.group]?.value ?: match.value
        // Collapse the whitespace gap left by the excised tag so e.g.
        // "[Artist] Title (C96) [English]" doesn't end up with double
        // space between "Title" and "[English]".
        val removed = ctx.filename.removeRange(match.range)
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        val reassembled = when (rule.position) {
            Rule.TagRelocator.Position.FRONT -> groupText + rule.separator + removed
            Rule.TagRelocator.Position.BACK -> removed + rule.separator + groupText
        }
        return RuleOutcome.Applied(ctx.withFilename(reassembled))
    }
}

internal object CleanWhitespaceExecutor : RuleExecutor<Rule.CleanWhitespace> {
    override fun apply(rule: Rule.CleanWhitespace, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val collapsed = ctx.filename.replace(Regex("\\s{2,}"), " ")
        val out = if (rule.trim) collapsed.trim() else collapsed
        return RuleOutcome.Applied(ctx.withFilename(out))
    }
}

internal object WriteComicInfoExecutor : RuleExecutor<Rule.WriteComicInfo> {
    override fun apply(rule: Rule.WriteComicInfo, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val resolved = rule.fields
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, template) -> interpolate(template, ctx.variables, false) }
        val note = if (resolved.isEmpty()) "(no fields configured)"
        else resolved.entries.joinToString(", ") { (k, v) -> "<$k>=$v" }
        return RuleOutcome.Applied(
            newContext = ctx.withComicInfoUpdates(resolved),
            note = note,
        )
    }
}

internal object GroupExecutor : RuleExecutor<Rule.Group> {
    override fun apply(rule: Rule.Group, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val nested = env.runNested(rule.rules, ctx)
        val note = if (rule.rules.isEmpty()) "Empty group"
        else "${rule.rules.size} nested rule${if (rule.rules.size == 1) "" else "s"}"
        return RuleOutcome.Applied(
            newContext = nested.finalContext,
            note = note,
            substeps = nested.steps,
        )
    }
}

internal object ConditionalFormatExecutor : RuleExecutor<Rule.ConditionalFormat> {
    override fun apply(rule: Rule.ConditionalFormat, ctx: PipelineContext, env: ExecutorEnv): RuleOutcome {
        val branchTaken = rule.condition.evaluate(ctx.variables)
        val branch = if (branchTaken) rule.thenRules else rule.elseRules
        val nested = env.runNested(branch, ctx)
        val note = "Branch: ${if (branchTaken) "THEN" else "ELSE"} (${branch.size} rule${if (branch.size == 1) "" else "s"})"
        return RuleOutcome.Applied(
            newContext = nested.finalContext,
            note = note,
            substeps = nested.steps,
        )
    }
}

/**
 * Default rule-type → executor map. Pinning to a [Rule] subclass at the
 * call site means dispatch is O(1) and adding a new rule means one
 * more entry here. Each executor is a singleton — they're stateless.
 */
val DEFAULT_RULE_EXECUTORS: Map<Class<out Rule>, RuleExecutor<*>> = mapOf(
    Rule.ExtractXmlMetadata::class.java to ExtractXmlMetadataExecutor,
    Rule.ExtractRegex::class.java to ExtractRegexExecutor,
    Rule.SetVariable::class.java to SetVariableExecutor,
    Rule.RegexReplace::class.java to RegexReplaceExecutor,
    Rule.StringAppend::class.java to StringAppendExecutor,
    Rule.StringPrepend::class.java to StringPrependExecutor,
    Rule.TagRelocator::class.java to TagRelocatorExecutor,
    Rule.CleanWhitespace::class.java to CleanWhitespaceExecutor,
    Rule.WriteComicInfo::class.java to WriteComicInfoExecutor,
    Rule.Group::class.java to GroupExecutor,
    Rule.ConditionalFormat::class.java to ConditionalFormatExecutor,
)
