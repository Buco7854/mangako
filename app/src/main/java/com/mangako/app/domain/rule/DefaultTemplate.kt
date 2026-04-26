package com.mangako.app.domain.rule

import java.util.UUID

/**
 * The opinionated "LANraragi Standard" pipeline shipped with the app.
 *
 * Architecture: read everything we need into variables (from ComicInfo
 * primarily, and from the detected filename for things ComicInfo
 * doesn't carry — event tags, emoji language flags), compute the
 * canonical %title%, then build the final filename with a single
 * template at the end. The previous shape mutated the filename string
 * through ten separate rules, which made it hard to see at a glance
 * what the canonical filename actually looked like — here it's one
 * string in one place.
 *
 * Phases:
 *   1. Extract — ComicInfo + Summary / emoji-flag language fallbacks +
 *                event tag pulled out of the detected filename
 *   2. Compute — fix the generic upstream title, append "Ch N" for manhwa
 *   3. Helpers — build the small bracket-tag suffixes used by the template
 *   4. Build  — assemble the filename in one SetVariable step
 *   5. Hygiene — strip illegal chars, collapse whitespace
 *   6. Sync   — write %title% back into ComicInfo, clear Series
 *
 * Everything below is editable once loaded — users can reorder, delete,
 * or override any rule from the UI.
 */
object DefaultTemplate {

    private fun id() = UUID.randomUUID().toString()

    /** The 12 target languages the pipeline normalises to. */
    private val LANG_ALT = "English|Japanese|Chinese|Korean|French|Spanish|German|Italian|Portuguese|Russian|Thai|Vietnamese"

    fun lanraragiStandard(): PipelineConfig = PipelineConfig(
        name = "LANraragi Standard",
        rules = buildList {
            // ─────────────────────────────────────────────
            // Phase 1: Extract metadata
            // ─────────────────────────────────────────────

            // 1. Read ComicInfo.xml → %title%, %series%, %writer%,
            //    %number%, %genre%, %language% (from <LanguageISO>),
            //    %summary%.
            add(Rule.ExtractXmlMetadata(id = id()))

            // 2. Fall back: pull language from <Summary> when
            //    <LanguageISO> is missing. Matches "Language: English"
            //    or "Languages: english". Defaults to English so the
            //    filename always has a [Language] tag.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Language fallback (from Summary)",
                    source = "summary",
                    target = "language",
                    pattern = "[Ll]anguages?:\\s*($LANG_ALT)",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "English",
                )
            )

            // 3. Language fallback (from emoji flag in detected
            //    filename). Some sources tag with 🇯🇵 etc. instead
            //    of populating ComicInfo. The outer condition guards
            //    so this only fires when steps 1 & 2 left %language%
            //    empty — but in practice step 2's defaultValue
            //    always sets English, so this fires only if a user
            //    deletes the Summary fallback rule. Kept anyway as
            //    a defensible default for unusual pipelines.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Language fallback (from emoji flag)",
                    condition = Condition(
                        variable = "language",
                        op = Condition.Op.IS_EMPTY,
                        value = "",
                    ),
                    thenRules = listOf(
                        emojiToLanguage("🇺🇸", "English"),
                        emojiToLanguage("🇬🇧", "English"),
                        emojiToLanguage("🇯🇵", "Japanese"),
                        emojiToLanguage("🇰🇷", "Korean"),
                        emojiToLanguage("🇨🇳", "Chinese"),
                        emojiToLanguage("🇹🇼", "Chinese"),
                        emojiToLanguage("🇫🇷", "French"),
                        emojiToLanguage("🇪🇸", "Spanish"),
                        emojiToLanguage("🇩🇪", "German"),
                        emojiToLanguage("🇮🇹", "Italian"),
                        emojiToLanguage("🇧🇷", "Portuguese"),
                        emojiToLanguage("🇵🇹", "Portuguese"),
                        emojiToLanguage("🇷🇺", "Russian"),
                        emojiToLanguage("🇹🇭", "Thai"),
                        emojiToLanguage("🇻🇳", "Vietnamese"),
                    ),
                )
            )

            // 4. Extract event / convention tag from the detected
            //    filename — "(C96)", "(Comiket 102)", "(COMITIA 145)"
            //    etc. Pulled out of the source filename and stashed
            //    in %event% so the build template can prefix it back
            //    onto the canonical name. Mirrors lrr-preprocess.sh's
            //    $event_regex list verbatim.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract event tag from filename",
                    source = "__filename__",
                    target = "event",
                    pattern = "\\((?:COMIC[^)]*|C\\d+|Comiket[^)]*|COMITIA[^)]*|Reitaisai[^)]*|SPARK[^)]*|GATE[^)]*|Futaket[^)]*|Shuuki[^)]*|Natsu[^)]*|Fuyu[^)]*)\\)",
                    group = 0,
                    ignoreCase = false,
                    onlyIfEmpty = false,
                    defaultValue = "",
                )
            )

            // 5. Compute %event_prefix% — "(C96) " (with trailing
            //    space) when an event tag was found, otherwise
            //    empty. Lets the build template stay a single
            //    string: "%event_prefix%[%writer%] …" with no
            //    conditional logic at template time.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Compute %event_prefix%",
                    condition = Condition(
                        variable = "event",
                        op = Condition.Op.IS_NOT_EMPTY,
                        value = "",
                    ),
                    thenRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "Event tag prefix on",
                            target = "event_prefix",
                            value = "%event% ",
                        ),
                    ),
                    elseRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "Event tag prefix off",
                            target = "event_prefix",
                            value = "",
                        ),
                    ),
                )
            )

            // ─────────────────────────────────────────────
            // Phase 2: Compute the human %title%
            // ─────────────────────────────────────────────

            // 6. Generic title fix: when Mihon embeds the chapter
            //    label as <Title> (e.g. "Chapter 39", "Ch.3"), promote
            //    %series% into %title%. Mirrors fix_comicinfo_title in
            //    mihon.sh.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Promote %series% into %title% (generic Mihon title)",
                    condition = Condition(
                        variable = "title",
                        op = Condition.Op.MATCHES,
                        value = "^(Chapter|Chapter\\s*\\d+(?:\\.\\d+)?|Chap\\s*\\d+(?:\\.\\d+)?|Ch\\.?\\s*\\d+(?:\\.\\d+)?)$",
                        ignoreCase = true,
                    ),
                    thenRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "Set %title% to %series%",
                            target = "title",
                            value = "%series%",
                        ),
                    ),
                )
            )

            // 7. Manhwa: append " Ch %number%" so the title carries
            //    chapter granularity ("My Series Ch 39"). The filename
            //    template below picks up %title% verbatim, so this is
            //    the single place that controls how chapter info
            //    appears in both filename and ComicInfo.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Append chapter to %title% (manhwa)",
                    condition = Condition(
                        variable = "genre",
                        op = Condition.Op.CONTAINS,
                        value = "Manhwa",
                    ),
                    thenRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "Set %title% to \"%title% Ch %number%\"",
                            target = "title",
                            value = "%title% Ch %number%",
                        ),
                    ),
                )
            )

            // ─────────────────────────────────────────────
            // Phase 3: Helper variables for the template
            // ─────────────────────────────────────────────

            // 8. Manhwa suffix tag — " [Manhwa]" for manhwa, blank
            //    otherwise. Pulled out as its own variable so the
            //    filename template stays a single string with no
            //    conditional logic.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Compute %manhwa_suffix%",
                    condition = Condition(
                        variable = "genre",
                        op = Condition.Op.CONTAINS,
                        value = "Manhwa",
                    ),
                    thenRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "[Manhwa] tag on",
                            target = "manhwa_suffix",
                            value = " [Manhwa]",
                        ),
                    ),
                    elseRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "[Manhwa] tag off",
                            target = "manhwa_suffix",
                            value = "",
                        ),
                    ),
                )
            )

            // ─────────────────────────────────────────────
            // Phase 4: Build the filename
            // ─────────────────────────────────────────────

            // 9. Compose the final filename in one place. Setting
            //    %__filename__% mutates the working filename string,
            //    so this single template is what becomes the upload
            //    name. Everything before this step prepares the
            //    variables it interpolates.
            add(
                Rule.SetVariable(
                    id = id(),
                    label = "Build filename",
                    target = "__filename__",
                    value = "%event_prefix%[%writer%] %title% [%language%]%manhwa_suffix%.cbz",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 5: Hygiene
            // ─────────────────────────────────────────────

            // 10. Strip Windows-unsafe filename chars so LANraragi's
            //     client-side dedup never chokes on a stray colon.
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Sanitize invalid chars",
                    pattern = "[\\\\/:*?\"<>|]",
                    replacement = "",
                )
            )

            // 11. Collapse runs of whitespace + trim. Catches the empty
            //     " [Manhwa]" / event-less cases where the template
            //     leaves a double space, plus any leading whitespace
            //     when there's no event prefix.
            add(Rule.CleanWhitespace(id = id(), trim = true))

            // ─────────────────────────────────────────────
            // Phase 6: Sync ComicInfo
            // ─────────────────────────────────────────────

            // 12. Write %title% into ComicInfo's <Title> and clear
            //     <Series>. Without the Title write, LANraragi's
            //     auto-extraction keeps showing Mihon's "Chapter 39";
            //     without the Series clear it groups uploads by
            //     Mihon's per-manga <Series> rather than letting the
            //     user organise them themselves. Mirrors
            //     fix_comicinfo_title + remove_comicinfo_series in
            //     mihon.sh.
            add(
                Rule.WriteComicInfo(
                    id = id(),
                    label = "Sync ComicInfo with title",
                    fields = mapOf(
                        "Title" to "%title%",
                        "Series" to "",
                    ),
                ),
            )
        },
    )

    /** Single emoji-flag → language ConditionalFormat used by the
     *  language fallback chain. Setting %language% inside a thenRules
     *  list keeps the inner Group flat in the editor. */
    private fun emojiToLanguage(emoji: String, lang: String): Rule = Rule.ConditionalFormat(
        id = id(),
        label = "$emoji → $lang",
        condition = Condition(
            variable = "__filename__",
            op = Condition.Op.CONTAINS,
            value = emoji,
        ),
        thenRules = listOf(
            Rule.SetVariable(
                id = id(),
                target = "language",
                value = lang,
            ),
        ),
    )
}
