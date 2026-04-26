package com.mangako.app.domain.rule

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Polymorphic root for every pipeline step. Each subclass carries the entire
 * configuration surface the user can tweak in the UI.
 *
 * Serialization uses kotlinx-serialization's class discriminator ("type") so
 * pipelines round-trip losslessly through JSON — the export/import format.
 */
@Serializable
sealed class Rule {
    abstract val id: String
    abstract val enabled: Boolean
    abstract val label: String?

    /** Short human name for the pipeline list. */
    abstract fun displayName(): String

    /** One-line description of what this rule will do, rendered in the builder. */
    abstract fun describe(): String

    /**
     * Subclass-polymorphic copy for the two metadata fields every rule carries.
     * Centralising this here means adding a new [Rule] subtype no longer requires
     * editing a `when` in every UI file.
     */
    abstract fun withMeta(enabled: Boolean = this.enabled, label: String? = this.label): Rule

    @Serializable
    @SerialName("extract_xml")
    data class ExtractXmlMetadata(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        /**
         * Variables to hoist from ComicInfo.xml. Key = variable token exposed to
         * downstream rules (e.g. "title"), value = XML element path.
         */
        val mappings: Map<String, String> = DEFAULT_MAPPINGS,
    ) : Rule() {
        override fun displayName() = label ?: "Extract XML Metadata"
        override fun describe() = "Read ComicInfo.xml → ${mappings.keys.joinToString { "%$it%" }}"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)

        companion object {
            val DEFAULT_MAPPINGS = mapOf(
                "title" to "Title",
                "series" to "Series",
                "writer" to "Writer",
                "number" to "Number",
                "genre" to "Genre",
                "language" to "LanguageISO",
                "publisher" to "Publisher",
            )
        }
    }

    /**
     * Captures a substring from one variable into another. Useful when the raw
     * metadata doesn't put a value in its own field — e.g. Mihon ComicInfo files
     * sometimes encode "Languages: English" inside <Summary> instead of setting
     * <LanguageISO>, and we want that value available as %language% downstream.
     *
     * If the target variable is already non-empty, [onlyIfEmpty] lets the rule
     * opt out of overwriting it. If the regex doesn't match and the target is
     * empty, [defaultValue] is used (also interpolated, so "%series%" works).
     */
    @Serializable
    @SerialName("extract_regex")
    data class ExtractRegex(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val source: String,
        val target: String,
        val pattern: String,
        val group: Int = 1,
        val ignoreCase: Boolean = true,
        val onlyIfEmpty: Boolean = true,
        val defaultValue: String = "",
    ) : Rule() {
        override fun displayName() = label ?: "Extract %$source% → %$target%"
        override fun describe() = "%$source% =~ /${pattern.truncate(24)}/ → %$target%"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("regex_replace")
    data class RegexReplace(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val pattern: String,
        val replacement: String,
        val ignoreCase: Boolean = false,
    ) : Rule() {
        override fun displayName() = label ?: "Regex Replace"
        override fun describe() = "s/${pattern.truncate(24)}/${replacement.truncate(24)}/"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    /**
     * A list of (pattern, replacement) regex substitutions applied in order to
     * the working filename. Conceptually identical to N consecutive [RegexReplace]
     * rules, but kept as a single user-visible step so a logically-grouped batch
     * (e.g. "emoji flag → [Language] tag" mappings) doesn't fill up the pipeline
     * list with N near-identical rows.
     */
    @Serializable
    @SerialName("regex_replace_many")
    data class RegexReplaceMany(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val replacements: List<Replacement> = emptyList(),
        val ignoreCase: Boolean = false,
    ) : Rule() {
        @Serializable
        data class Replacement(val pattern: String, val replacement: String)

        override fun displayName() = label ?: "Find & replace (group)"
        override fun describe() = when (replacements.size) {
            0 -> "(no replacements yet)"
            1 -> "1 replacement"
            else -> "${replacements.size} replacements"
        }
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("append")
    data class StringAppend(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val text: String,
    ) : Rule() {
        override fun displayName() = label ?: "Append"
        override fun describe() = "… + \"${text.truncate(32)}\""
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("prepend")
    data class StringPrepend(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val text: String,
    ) : Rule() {
        override fun displayName() = label ?: "Prepend"
        override fun describe() = "\"${text.truncate(32)}\" + …"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("tag_relocator")
    data class TagRelocator(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val pattern: String,
        val position: Position = Position.FRONT,
        /** Index of the captured group to relocate. 0 = whole match. */
        val group: Int = 0,
        val separator: String = " ",
    ) : Rule() {
        @Serializable
        enum class Position { FRONT, BACK }

        override fun displayName() = label ?: "Tag Relocator"
        override fun describe() = "Move /${pattern.truncate(20)}/ to $position"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("conditional")
    data class ConditionalFormat(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val condition: Condition,
        val thenRules: List<Rule> = emptyList(),
        val elseRules: List<Rule> = emptyList(),
    ) : Rule() {
        override fun displayName() = label ?: "Conditional"
        override fun describe() = "if ${condition.describe()} then ${thenRules.size} rule(s)"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    @Serializable
    @SerialName("clean_whitespace")
    data class CleanWhitespace(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        /** Also trim leading/trailing whitespace from the final string. */
        val trim: Boolean = true,
    ) : Rule() {
        override fun displayName() = label ?: "Clean Whitespace"
        override fun describe() = "Collapse repeated spaces${if (trim) " + trim" else ""}"
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }
}

@Serializable
data class Condition(
    val variable: String,
    val op: Op,
    val value: String,
    val ignoreCase: Boolean = true,
) {
    @Serializable
    enum class Op { EQUALS, CONTAINS, MATCHES, NOT_EQUALS, NOT_CONTAINS, IS_EMPTY, IS_NOT_EMPTY }

    fun describe(): String = when (op) {
        Op.IS_EMPTY -> "%$variable% is empty"
        Op.IS_NOT_EMPTY -> "%$variable% is not empty"
        else -> "%$variable% $op \"$value\""
    }

    fun evaluate(variables: Map<String, String>): Boolean {
        val v = variables[variable].orEmpty()
        val cmp = if (ignoreCase) String.CASE_INSENSITIVE_ORDER else Comparator<String> { a, b -> a.compareTo(b) }
        return when (op) {
            Op.EQUALS -> cmp.compare(v, value) == 0
            Op.NOT_EQUALS -> cmp.compare(v, value) != 0
            Op.CONTAINS -> v.contains(value, ignoreCase)
            Op.NOT_CONTAINS -> !v.contains(value, ignoreCase)
            Op.MATCHES -> runCatching {
                Regex(value, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(v)
            }.getOrDefault(false)
            Op.IS_EMPTY -> v.isBlank()
            Op.IS_NOT_EMPTY -> v.isNotBlank()
        }
    }
}

private fun String.truncate(n: Int): String =
    if (length <= n) this else take(n - 1) + "…"
