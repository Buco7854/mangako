package com.mangako.app.domain.rule

import java.util.UUID

/**
 * The opinionated "LANraragi Standard" pipeline shipped with the app.
 *
 * Architecture: read everything we need from ComicInfo into variables,
 * compute the human title (`%title%`), then build the final filename
 * with a single template at the end. The previous shape mutated the
 * filename string through ten separate steps, which made it hard to
 * see at a glance what the canonical filename actually looked like —
 * here it's one string in one place.
 *
 * Phases:
 *   1. Extract — pull metadata from ComicInfo, fall back via Summary
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

            // ─────────────────────────────────────────────
            // Phase 2: Compute the human %title%
            // ─────────────────────────────────────────────

            // 3. Generic title fix: when Mihon embeds the chapter
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

            // 4. Manhwa: append " Ch %number%" so the title carries
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

            // 5. Manhwa suffix tag — " [Manhwa]" for manhwa, blank
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

            // 6. Compose the final filename in one place. Setting
            //    %__filename__% mutates the working filename string,
            //    so this single template is what becomes the upload
            //    name. Everything before this step prepares the
            //    variables it interpolates.
            add(
                Rule.SetVariable(
                    id = id(),
                    label = "Build filename",
                    target = "__filename__",
                    value = "[%writer%] %title% [%language%]%manhwa_suffix%.cbz",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 5: Hygiene
            // ─────────────────────────────────────────────

            // 7. Strip Windows-unsafe filename chars so LANraragi's
            //    client-side dedup never chokes on a stray colon.
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Sanitize invalid chars",
                    pattern = "[\\\\/:*?\"<>|]",
                    replacement = "",
                )
            )

            // 8. Collapse runs of whitespace + trim. Catches the empty
            //    " [Manhwa]" / writer-less cases where the template
            //    leaves a double space.
            add(Rule.CleanWhitespace(id = id(), trim = true))

            // ─────────────────────────────────────────────
            // Phase 6: Sync ComicInfo
            // ─────────────────────────────────────────────

            // 9. Write %title% into ComicInfo's <Title> and clear
            //    <Series>. Without the Title write, LANraragi's
            //    auto-extraction keeps showing Mihon's "Chapter 39";
            //    without the Series clear it groups uploads by
            //    Mihon's per-manga <Series> rather than letting the
            //    user organise them themselves. Mirrors
            //    fix_comicinfo_title + remove_comicinfo_series in
            //    mihon.sh.
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
}
