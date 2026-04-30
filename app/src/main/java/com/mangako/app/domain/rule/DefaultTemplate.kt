package com.mangako.app.domain.rule

import java.util.UUID

/**
 * The opinionated "LANraragi Standard" pipeline shipped with the app.
 *
 * Architecture: read the basic ComicInfo fields, promote <Series> into
 * <Title> when the upstream Title is the generic "Chapter" label Mihon
 * embeds, then run every tag-extraction (event, language, trailing
 * tags) against %title% — which by that point is the bracket-decorated
 * filename-shaped string Mihon's structured sources produce. Once the
 * tags are out, strip the brackets out of %title% to leave just the
 * human title, append "Ch N" for manhwa, and assemble the final
 * filename in one template step.
 *
 * Phases:
 *   1. Read         — ExtractXmlMetadata
 *   2. Promote      — series → title when title is generic
 *   3. Extract      — language, event, trailing tags from %title%
 *   4. Clean title  — strip brackets out so %title% holds just the meat
 *   5. Manhwa       — append "Ch N" to %title%, ensure [Manhwa] in tags
 *   6. Build        — assemble the filename in one SetVariable step
 *   7. Hygiene      — strip illegal chars, collapse whitespace
 *   8. Sync         — write %title% back into ComicInfo, clear Series
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
            // Phase 1: Read base metadata
            // ─────────────────────────────────────────────

            // 1. Read ComicInfo.xml → %title%, %series%, %writer%,
            //    %number%, %genre%, %language% (from <LanguageISO>),
            //    %summary%.
            add(Rule.ExtractXmlMetadata(id = id()))

            // ─────────────────────────────────────────────
            // Phase 2: Promote <Series> into <Title> if generic
            // ─────────────────────────────────────────────

            // 2. When Mihon embeds the chapter label as <Title>
            //    ("Chapter 39", "Ch.3", etc.), the rich data lives
            //    in <Series> instead. Hoist the whole <Series>
            //    string into %title% verbatim so the extractors
            //    below can mine it for tags, language, and event
            //    info. Mirrors fix_comicinfo_title in mihon.sh.
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

            // ─────────────────────────────────────────────
            // Phase 3: Extract tags from %title%
            // ─────────────────────────────────────────────

            // 3. Language fallback: pull the [Lang] bracket out of
            //    %title% when <LanguageISO> didn't already set it.
            //    For Mihon NHentai-style sources, by this point
            //    %title% reads "[Author] Real Title [English]
            //    [Group]" and this catches the [English] bracket.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Language fallback (from Title)",
                    source = "title",
                    target = "language",
                    pattern = "\\[($LANG_ALT)\\]",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // 4. Language fallback: detect a flag emoji in
            //    %title%. Some doujin sources tag with "🇯🇵" or
            //    "🇬🇧" instead of populating <LanguageISO>; by
            //    this point Phase 2 has consolidated the rich
            //    string into %title% (whether it lived in <Title>
            //    or got promoted from <Series>) so this catches
            //    the emoji from either source. Outer condition
            //    guards so this only fires when nothing earlier
            //    set %language%; placed before the Summary
            //    fallback so its English default doesn't pre-empt
            //    the emoji read.
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

            // 5. Language fallback: pull the language out of
            //    <Summary> when nothing earlier set it. Matches
            //    "Language: English" or "Languages: english".
            //    Defaults to English so the filename always gets a
            //    [Language] tag.
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

            // 6. Extract event / convention tag — "(C96)",
            //    "(Comiket 102)", "(COMITIA 145)" — from %title%.
            //    Mirrors lrr-preprocess.sh's $event_regex list
            //    verbatim. onlyIfEmpty=true so a user-supplied
            //    %event% from the Inbox edit-detection sheet wins.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract event tag from Title",
                    source = "title",
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
            //    filename for files without trailing tags. Wrapped
            //    in an IS_EMPTY guard so a user-supplied override
            //    survives.
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

            // 9. Pull trailing tags after the language bracket out
            //    of %title%. For "[Author] Real Title [English]
            //    [Project Valvrein]", this captures " [Project
            //    Valvrein]" — the translator/group tag that
            //    belongs on the filename but not on the human
            //    title. Captured verbatim (with leading space) so
            //    the build template can drop it in directly.
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Extract trailing tags from Title",
                    source = "title",
                    target = "extra_tags",
                    pattern = "\\[(?:$LANG_ALT)\\](.*)$",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 4: Clean %title%
            // ─────────────────────────────────────────────

            // 10. Strip brackets out of %title% so it holds just the
            //    human title meat. Drops a leading "(C96) " event
            //    tag and "[Artist]" tag, plus any trailing
            //    "[Lang] [Group]" brackets. Falls back to %title%
            //    verbatim if there's no bracket structure (regular
            //    manga case where <Title> was already clean).
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Strip brackets out of %title%",
                    source = "title",
                    target = "title",
                    pattern = "^(?:\\([^)]*\\)\\s+)?(?:\\[[^\\]]+\\]\\s+)?(.+?)(?:\\s+\\[[^\\]]+\\])*\\s*$",
                    group = 1,
                    ignoreCase = false,
                    onlyIfEmpty = false,
                    defaultValue = "%title%",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 5: Manhwa enhancements
            // ─────────────────────────────────────────────

            // 11. Manhwa: append " Ch %number%" so the title
            //     carries chapter granularity ("My Series Ch 39").
            //     The filename template below picks up %title%
            //     verbatim, so this is the single place that
            //     controls how chapter info appears in both
            //     filename and ComicInfo.
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

            // 12. Manhwa: ensure "[Manhwa]" lives in %extra_tags%
            //     when genre says manhwa AND it isn't there
            //     already. Routing the manhwa decision through
            //     %extra_tags% automatically dedupes a [Manhwa]
            //     that was already on the source.
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
            // Phase 6: Build the filename
            // ─────────────────────────────────────────────

            // 13. Compose the final filename in one place. Setting
            //     %__filename__% mutates the working filename
            //     string, so this single template is what becomes
            //     the upload name. Everything before this step
            //     prepares the variables it interpolates.
            //
            //     %extra_tags% already includes its own leading
            //     space (captured verbatim from %title%, or
            //     prepended by the manhwa step above), so the
            //     template doesn't add one before it.
            add(
                Rule.SetVariable(
                    id = id(),
                    label = "Build filename",
                    target = "__filename__",
                    value = "%event_prefix%[%writer%] %title% [%language%]%extra_tags%.cbz",
                )
            )

            // ─────────────────────────────────────────────
            // Phase 7: Hygiene
            // ─────────────────────────────────────────────

            // 14. Strip Windows-unsafe filename chars so LANraragi's
            //     client-side dedup never chokes on a stray colon
            //     (and the volume stays portable to NTFS-mounted
            //     backups). Only runs against the filename string —
            //     %title% keeps its original chars, so a "|" in a
            //     romaji-vs-English title separator survives into
            //     ComicInfo's <Title>.
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Sanitize invalid chars",
                    pattern = "[\\\\/:*?\"<>|]",
                    replacement = "",
                )
            )

            // 15. Collapse runs of whitespace + trim. Catches the
            //     empty event-prefix case where the template leaves
            //     a leading space, plus any double spaces from the
            //     sanitise step removing illegal chars from inside
            //     the title.
            add(Rule.CleanWhitespace(id = id(), trim = true))

            // ─────────────────────────────────────────────
            // Phase 8: Sync ComicInfo
            // ─────────────────────────────────────────────

            // 16. Write %title% into ComicInfo's <Title> and clear
            //     <Series>. Without the Title write, LANraragi's
            //     auto-extraction keeps showing Mihon's "Chapter
            //     39"; without the Series clear it groups uploads
            //     by Mihon's per-manga <Series> rather than letting
            //     the user organise them themselves. Mirrors
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
     *  language-fallback chain. Tests %title% for [emoji]; if
     *  present, sets %language% to [lang]. By the time this fires,
     *  Phase 2 has already promoted <Series> into %title% when the
     *  upstream Title was generic, so this catches a flag emoji
     *  living in either field. Kept flat (not a Group) so users
     *  can spot and remove individual mappings. */
    private fun emojiToLanguage(emoji: String, lang: String): Rule = Rule.ConditionalFormat(
        id = id(),
        label = "$emoji → $lang",
        condition = Condition(
            variable = "title",
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
