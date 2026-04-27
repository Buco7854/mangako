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

            // 2. Fall back: pull language from <Series> when
            //    <LanguageISO> is missing. Mihon's NHentai source (and
            //    similar) doesn't populate LanguageISO but writes a
            //    structured Series like "[Author] Title [English]
            //    [Group]". This grabs the [Language] tag out of that.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Language fallback (from Series)",
                    source = "series",
                    target = "language",
                    pattern = "\\[($LANG_ALT)\\]",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // 3. Fall back: pull language from <Summary> when nothing
            //    earlier set it. Matches "Language: English" or
            //    "Languages: english". Defaults to English so the
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

            // 4. Language fallback (from emoji flag in detected
            //    filename). Some sources tag with 🇯🇵 etc. instead
            //    of populating ComicInfo. Outer condition guards so
            //    this only fires when steps 1–3 left %language%
            //    empty — but in practice step 3's defaultValue
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

            // 5. Extract event / convention tag, preferring <Series>
            //    over the detected filename. Mihon's NHentai source
            //    sometimes prefixes the Series with "(C96) [Artist]
            //    Title …", and that's a more reliable source than
            //    the filename which may just be "Chapter.cbz".
            //    onlyIfEmpty=true so a user-supplied %event% from
            //    the Inbox edit-detection sheet wins.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract event tag from Series",
                    source = "series",
                    target = "event",
                    pattern = "\\((?:COMIC[^)]*|C\\d+|Comiket[^)]*|COMITIA[^)]*|Reitaisai[^)]*|SPARK[^)]*|GATE[^)]*|Futaket[^)]*|Shuuki[^)]*|Natsu[^)]*|Fuyu[^)]*)\\)",
                    group = 0,
                    ignoreCase = false,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // 6. Same extraction against the detected filename, only
            //    if Series didn't already provide one. Mirrors
            //    lrr-preprocess.sh's $event_regex list verbatim.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract event tag from filename",
                    source = "__filename__",
                    target = "event",
                    pattern = "\\((?:COMIC[^)]*|C\\d+|Comiket[^)]*|COMITIA[^)]*|Reitaisai[^)]*|SPARK[^)]*|GATE[^)]*|Futaket[^)]*|Shuuki[^)]*|Natsu[^)]*|Fuyu[^)]*)\\)",
                    group = 0,
                    ignoreCase = false,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // 7. Compute %event_prefix% — "(C96) " (with trailing
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

            // 8. Initialise %extra_tags% to empty when nothing else
            //    has set it. ExtractRegex below skips when both the
            //    capture and the default value are blank (its "no
            //    match and no default" short-circuit), and we don't
            //    want the literal "%extra_tags%" leaking into the
            //    filename for files without trailing tags. Wrapped in
            //    an IS_EMPTY guard so a user-supplied override from
            //    the Inbox edit-detection sheet survives.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Initialise %extra_tags%",
                    condition = Condition(
                        variable = "extra_tags",
                        op = Condition.Op.IS_EMPTY,
                        value = "",
                    ),
                    thenRules = listOf(
                        Rule.SetVariable(
                            id = id(),
                            label = "Default %extra_tags% to empty",
                            target = "extra_tags",
                            value = "",
                        ),
                    ),
                )
            )

            // 9. Pull trailing tags out of <Series> when it carries
            //    them — Mihon's NHentai writes Series like "[Artist]
            //    Title [English] [Project Valvrein]", and the
            //    "[Project Valvrein]" group / translator tag belongs
            //    on the rebuilt filename even though the detected
            //    .cbz name is just "Chapter.cbz". Captures
            //    everything between the language bracket and the end
            //    of Series, with leading space, ready to drop into
            //    the build template.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract trailing tags from Series",
                    source = "series",
                    target = "extra_tags",
                    pattern = "\\[(?:$LANG_ALT)\\](.*)$",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // 10. Same extraction against the detected filename, only
            //     if Series didn't already produce trailing tags.
            //     Captures everything between "[<lang>]" and ".cbz"
            //     verbatim.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract trailing tags from filename",
                    source = "__filename__",
                    target = "extra_tags",
                    pattern = "\\[(?:$LANG_ALT)\\](.*)\\.cbz$",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 2: Compute the human %title%
            // ─────────────────────────────────────────────

            // 11. Generic title fix: when Mihon embeds the chapter
            //     label as <Title> (e.g. "Chapter 39", "Ch.3"),
            //     promote a cleaned-up %series% into %title%.
            //     Mirrors fix_comicinfo_title in mihon.sh.
            //
            //     "Cleaned-up" means: strip a leading "[Artist]" tag
            //     and any trailing "[Lang] [Group]" brackets so we
            //     end up with just the title meat. For Mihon's
            //     NHentai source <Series> reads
            //     "[Jzargo] Shizue Sonoato. | Shizue afterwards
            //     [English] [Project Valvrein]" — we want %title%
            //     to be just "Shizue Sonoato. | Shizue afterwards",
            //     not the whole bracket-decorated string. Falls
            //     back to %series% verbatim if the regex doesn't
            //     match a bracket structure.
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
                        Rule.ExtractRegex(
                            id = id(),
                            label = "Strip brackets out of %series% into %title%",
                            source = "series",
                            target = "title",
                            pattern = "^(?:\\([^)]*\\)\\s+)?(?:\\[[^\\]]+\\]\\s+)?(.+?)(?:\\s+\\[[^\\]]+\\])*\\s*$",
                            group = 1,
                            ignoreCase = false,
                            onlyIfEmpty = false,
                            defaultValue = "%series%",
                        ),
                    ),
                )
            )

            // 12. Manhwa: append " Ch %number%" so the title
            //     carries chapter granularity ("My Series Ch 39").
            //     The filename template below picks up %title%
            //     verbatim, so this is the single place that
            //     controls how chapter info appears in both filename
            //     and ComicInfo.
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

            // 13. Manhwa: ensure "[Manhwa]" lives in %extra_tags%
            //     when genre says manhwa AND it isn't there
            //     already. %extra_tags% is the same bag of trailing
            //     tags detection extracted, so by routing the
            //     manhwa decision through it we automatically
            //     dedupe a [Manhwa] that was already on the source
            //     filename or in <Series>.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Ensure [Manhwa] in %extra_tags%",
                    condition = Condition(
                        variable = "genre",
                        op = Condition.Op.CONTAINS,
                        value = "Manhwa",
                    ),
                    thenRules = listOf(
                        Rule.ConditionalFormat(
                            id = id(),
                            label = "[Manhwa] not yet in trailing tags",
                            condition = Condition(
                                variable = "extra_tags",
                                op = Condition.Op.NOT_CONTAINS,
                                value = "[Manhwa]",
                                ignoreCase = true,
                            ),
                            thenRules = listOf(
                                Rule.SetVariable(
                                    id = id(),
                                    label = "Append [Manhwa] to %extra_tags%",
                                    target = "extra_tags",
                                    value = "%extra_tags% [Manhwa]",
                                ),
                            ),
                        ),
                    ),
                )
            )

            // ─────────────────────────────────────────────
            // Phase 4: Build the filename
            // ─────────────────────────────────────────────

            // 14. Compose the final filename in one place. Setting
            //     %__filename__% mutates the working filename
            //     string, so this single template is what becomes
            //     the upload name. Everything before this step
            //     prepares the variables it interpolates.
            //
            //     %extra_tags% already includes its own leading
            //     space (captured verbatim from Series or the
            //     filename, or prepended by the manhwa step above),
            //     so the template doesn't add one before it.
            add(
                Rule.SetVariable(
                    id = id(),
                    label = "Build filename",
                    target = "__filename__",
                    value = "%event_prefix%[%writer%] %title% [%language%]%extra_tags%.cbz",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 5: Hygiene
            // ─────────────────────────────────────────────

            // 15. Strip Windows-unsafe filename chars so LANraragi's
            //     client-side dedup never chokes on a stray colon
            //     (and the volume stays portable to NTFS-mounted
            //     backups). Note: this only runs against the
            //     filename string — the underlying %title%
            //     variable keeps its original chars, so e.g. a "|"
            //     in a romaji-vs-English title separator survives
            //     into ComicInfo's <Title>.
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Sanitize invalid chars",
                    pattern = "[\\\\/:*?\"<>|]",
                    replacement = "",
                )
            )

            // 16. Collapse runs of whitespace + trim. Catches the
            //     empty event-prefix case where the template leaves
            //     a leading space, plus any double spaces from the
            //     sanitise step removing illegal chars from inside
            //     the title.
            add(Rule.CleanWhitespace(id = id(), trim = true))

            // ─────────────────────────────────────────────
            // Phase 6: Sync ComicInfo
            // ─────────────────────────────────────────────

            // 17. Write %title% into ComicInfo's <Title> and clear
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
