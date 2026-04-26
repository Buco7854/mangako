package com.mangako.app.ui.format

import com.mangako.app.domain.pipeline.AuditStep
import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.Rule

/**
 * Plain-English summaries of pipeline rules, conditions, and audit steps.
 * Lives next to the UI because the wording is presentation, not domain logic —
 * the domain model carries technical fields (regex, group index, target var)
 * that are meaningless to a non-developer end user.
 */

fun Rule.humanTitle(): String = label?.takeIf { it.isNotBlank() } ?: when (this) {
    is Rule.ExtractXmlMetadata -> "Read details from ComicInfo.xml"
    is Rule.ExtractRegex -> "Pull a value out of %$source%"
    is Rule.RegexReplace -> "Find and replace text"
    is Rule.RegexReplaceMany -> "Find and replace (group)"
    is Rule.StringAppend -> "Add text to the end"
    is Rule.StringPrepend -> "Add text to the start"
    is Rule.TagRelocator -> when (position) {
        Rule.TagRelocator.Position.FRONT -> "Move a tag to the front"
        Rule.TagRelocator.Position.BACK -> "Move a tag to the end"
    }
    is Rule.ConditionalFormat -> "If/else"
    is Rule.CleanWhitespace -> "Tidy whitespace"
    is Rule.SectionHeader -> label ?: "Section"
}

fun Rule.humanSubtitle(): String = when (this) {
    is Rule.ExtractXmlMetadata ->
        "Loads ${mappings.keys.joinToString(", ") { "%$it%" }} from the archive."
    is Rule.ExtractRegex -> {
        val pat = pattern.ifBlank { "(no pattern yet)" }
        "Look in %$source% for /$pat/ and save the match into %$target%."
    }
    is Rule.RegexReplace -> {
        val pat = pattern.ifBlank { "(no pattern yet)" }
        val rep = replacement.ifBlank { "(empty)" }
        "Replace /$pat/ with \"$rep\"."
    }
    is Rule.RegexReplaceMany -> when (replacements.size) {
        0 -> "No replacements yet — tap to add some."
        1 -> "1 find-and-replace pair."
        else -> "${replacements.size} find-and-replace pairs applied in order."
    }
    is Rule.StringAppend -> if (text.isBlank()) "Append nothing yet." else "Append \"$text\" to the filename."
    is Rule.StringPrepend -> if (text.isBlank()) "Prepend nothing yet." else "Prepend \"$text\" to the filename."
    is Rule.TagRelocator -> {
        val pat = pattern.ifBlank { "(no pattern yet)" }
        val where = if (position == Rule.TagRelocator.Position.FRONT) "front" else "end"
        "Move the first thing matching /$pat/ to the $where of the filename."
    }
    is Rule.ConditionalFormat ->
        "If ${condition.humanize()}, run ${thenRules.size} rule${plural(thenRules.size)};" +
            " otherwise run ${elseRules.size}."
    is Rule.CleanWhitespace ->
        if (trim) "Collapse repeated spaces and trim the ends."
        else "Collapse repeated spaces."
    is Rule.SectionHeader -> "Visual divider — purely cosmetic."
}

fun Condition.humanize(): String {
    val v = "%$variable%"
    return when (op) {
        Condition.Op.EQUALS -> "$v equals \"$value\""
        Condition.Op.NOT_EQUALS -> "$v does not equal \"$value\""
        Condition.Op.CONTAINS -> "$v contains \"$value\""
        Condition.Op.NOT_CONTAINS -> "$v does not contain \"$value\""
        Condition.Op.MATCHES -> "$v matches /$value/"
        Condition.Op.IS_EMPTY -> "$v is empty"
        Condition.Op.IS_NOT_EMPTY -> "$v has a value"
    }
}

/**
 * Friendly summary of a single pipeline step for the History detail screen.
 * Returns a Pair (headline, detail) — headline always renders, detail is null
 * when the step is genuinely empty (e.g. a no-op that needs no extra words).
 */
fun AuditStep.humanize(): HumanizedStep {
    val title = ruleLabel.takeIf { it.isNotBlank() } ?: ruleType.humanizeRuleType()
    val verdict = when {
        skipped -> "Skipped — ${humanizeSkipReason(skippedReason)}"
        !changed -> "No change — the filename already looked right."
        else -> "Renamed."
    }
    return HumanizedStep(title = title, verdict = verdict)
}

data class HumanizedStep(
    val title: String,
    val verdict: String,
)

private fun String.humanizeRuleType(): String = when (this) {
    "ExtractXmlMetadata" -> "Read ComicInfo.xml"
    "ExtractRegex" -> "Extract value"
    "RegexReplace" -> "Find and replace"
    "RegexReplaceMany" -> "Find and replace (group)"
    "StringAppend" -> "Append text"
    "StringPrepend" -> "Prepend text"
    "TagRelocator" -> "Reposition tag"
    "ConditionalFormat" -> "If/else"
    "CleanWhitespace" -> "Tidy whitespace"
    "SectionHeader" -> "Section"
    else -> this
}

private fun humanizeSkipReason(raw: String?): String = when {
    raw.isNullOrBlank() -> "no work to do."
    raw == "Rule disabled" -> "the rule is turned off."
    raw == "No match" -> "the pattern didn't find anything to change."
    raw.startsWith("No match and no default") -> "the pattern didn't match and no default was set."
    raw.startsWith("Rule exceeded") -> "the pattern took too long (it may be a runaway regex)."
    raw.startsWith("%") && raw.endsWith(" already set") -> "the target variable already had a value."
    raw.startsWith("Error: ") -> "the rule errored out (${raw.removePrefix("Error: ")})."
    else -> raw.replaceFirstChar { it.lowercase() }.trimEnd('.').plus('.')
}

private fun plural(n: Int) = if (n == 1) "" else "s"
