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

    /**
     * Sets a single variable to a (interpolated) template string. Lets a
     * pipeline propagate a corrected value forward without reading from
     * source data — e.g. `%title% = %series%` after an upstream "fix
     * generic titles" step. The value template can reference any
     * variable that's already in scope, including the reserved
     * `%__filename__%` / `%__filename_stem__%`.
     *
     * Logically it's the simplest possible mutation: assign a value to
     * a name. Modeled as its own rule type rather than a special-case
     * regex extraction so the editor and audit log can describe it
     * plainly ("Set %title% to %series%") instead of via empty-pattern
     * tricks.
     */
    @Serializable
    @SerialName("set_variable")
    data class SetVariable(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val target: String,
        val value: String,
    ) : Rule() {
        override fun displayName() = label ?: "Set %$target%"
        override fun describe() = "%$target% = ${value.truncate(32)}"
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
     * Container of nested rules. Tap-to-edit opens an editor that lists the
     * sub-rules and lets the user add / reorder / remove them — same shape
     * as [ConditionalFormat]'s then/else sections but without the
     * conditional gate. Useful for clustering N related steps under a
     * label (e.g. 'Emoji flags → languages', 'Manhwa formatting') so the
     * pipeline list doesn't sprawl.
     *
     * Execution: each sub-rule runs in order against the current
     * filename + variables, and any side effects (variable updates,
     * ComicInfo writes) bubble up to the parent.
     */
    @Serializable
    @SerialName("group")
    data class Group(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val rules: List<Rule> = emptyList(),
    ) : Rule() {
        override fun displayName() = label ?: "Group"
        override fun describe() = when (rules.size) {
            0 -> "(empty group)"
            1 -> "1 nested rule"
            else -> "${rules.size} nested rules"
        }
        override fun withMeta(enabled: Boolean, label: String?) = copy(enabled = enabled, label = label)
    }

    /**
     * Writes (or overwrites) elements inside the archive's ComicInfo.xml
     * after the pipeline runs. Each entry in [fields] is `<KeyName>` →
     * value template; the template is `%var%`-interpolated against the
     * pipeline variables, so e.g. `Title=%__filename_stem__%` will set
     * `<Title>` to whatever the rename pipeline produced (without the
     * `.cbz` extension).
     *
     * The rule itself records the requested writes as a side effect on
     * the [PipelineExecutor.Output]; the actual file rewrite happens in
     * [com.mangako.app.work.ProcessCbzWorker] after the pipeline
     * finishes, so this rule has no effect on the working filename.
     */
    @Serializable
    @SerialName("write_comicinfo")
    data class WriteComicInfo(
        override val id: String,
        override val enabled: Boolean = true,
        override val label: String? = null,
        val fields: Map<String, String> = emptyMap(),
    ) : Rule() {
        override fun displayName() = label ?: "Write to ComicInfo.xml"
        override fun describe() = when (fields.size) {
            0 -> "(no fields configured)"
            1 -> "Set <${fields.keys.first()}> in ComicInfo.xml"
            else -> "Set ${fields.size} fields in ComicInfo.xml"
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
