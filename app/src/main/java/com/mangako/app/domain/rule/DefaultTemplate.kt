package com.mangako.app.domain.rule

import java.util.UUID

/**
 * The opinionated "LANraragi Standard" pipeline shipped with the app. This is
 * a rename-only port of two bash scripts the user runs server-side:
 *   - lrr-preprocess.sh (emoji flags, event repositioning, language fix, whitespace)
 *   - mihon.sh          (ComicInfo.xml enrichment: title fix, writer prepend,
 *                        language from Summary, manhwa formatting)
 *
 * Deliberate non-goals:
 *   1. In-place ComicInfo.xml rewriting (fix_comicinfo_title, remove_comicinfo_series).
 *      Mangako's pipeline only renames; it does not repack archives.
 *   2. Cross-folder grouping by <Series>. Mangako processes one .cbz at a time;
 *      each file's own ComicInfo carries Series + Number, which is enough to
 *      produce identical filenames on a per-file basis.
 *
 * Everything below is editable once loaded — users can reorder, delete, or
 * override any rule from the UI.
 */
object DefaultTemplate {

    private fun id() = UUID.randomUUID().toString()

    /** The 12 target languages the scripts normalise filenames to. */
    private val LANG_ALT = "English|Japanese|Chinese|Korean|French|Spanish|German|Italian|Portuguese|Russian|Thai|Vietnamese"

    fun lanraragiStandard(): PipelineConfig = PipelineConfig(
        name = "LANraragi Standard",
        rules = buildList {
            // 1. Read ComicInfo.xml → variables (Title, Series, Writer, Number, Genre, LanguageISO as %language%, Summary).
            add(Rule.ExtractXmlMetadata(id = id()))

            // 2. Fall back: extract language from <Summary> when <LanguageISO> is missing.
            //    Matches "Language: English" or "Languages: english".
            add(
                Rule.ExtractRegex(
                    id = id(),
                    label = "Language from Summary",
                    source = "summary",
                    target = "language",
                    pattern = "[Ll]anguages?:\\s*(English|Japanese|Chinese|Korean|French|Spanish|German|Italian|Portuguese|Russian|Thai|Vietnamese)",
                    group = 1,
                    ignoreCase = true,
                    onlyIfEmpty = true,
                    defaultValue = "English",
                )
            )

            // 3. Generic title fix: if <Title> looks like "Chapter", "Chapter 12", "Chap 5", "Ch.3" — replace it with %series%.
            //    We rewrite the whole filename, since our input filename is typically <title>.cbz from Mihon.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Fix generic titles",
                    condition = Condition(
                        variable = "title",
                        op = Condition.Op.MATCHES,
                        value = "^(Chapter|Chapter\\s*\\d+(?:\\.\\d+)?|Chap\\s*\\d+(?:\\.\\d+)?|Ch\\.?\\s*\\d+(?:\\.\\d+)?)$",
                        ignoreCase = true,
                    ),
                    thenRules = listOf(
                        Rule.RegexReplace(
                            id = id(),
                            label = "Replace filename with %series%",
                            pattern = "^.*\\.cbz$",
                            replacement = "%series%.cbz",
                            ignoreCase = true,
                        ),
                    ),
                )
            )

            // 4. Prepend [Writer] when filename lacks a leading bracket tag (artist/circle).
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Prepend [Writer] if absent",
                    condition = Condition(
                        variable = "__filename__",
                        op = Condition.Op.MATCHES,
                        value = "^\\s*\\[",
                    ),
                    elseRules = listOf(
                        Rule.StringPrepend(id = id(), label = "Prepend [%writer%]", text = "[%writer%] "),
                    ),
                )
            )

            // 5. Strip Windows-unsafe filename chars so LANraragi's client dedup never chokes.
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Sanitize invalid chars",
                    pattern = "[\\\\/:*?\"<>|]",
                    replacement = "",
                )
            )

            // 6. Emoji flags → [Language]. Mirrors lrr-preprocess.sh clean_name().
            //    A Group keeps the twelve mappings as one user-visible step
            //    in the pipeline list — tap it to see / edit the individual
            //    RegexReplace sub-rules.
            add(
                Rule.Group(
                    id = id(),
                    label = "Emoji flags → [Language]",
                    rules = listOf(
                        Rule.RegexReplace(id = id(), label = "🇺🇸/🇬🇧 → [English]", pattern = "🇺🇸|🇬🇧", replacement = "[English]"),
                        Rule.RegexReplace(id = id(), label = "🇯🇵 → [Japanese]", pattern = "🇯🇵", replacement = "[Japanese]"),
                        Rule.RegexReplace(id = id(), label = "🇰🇷 → [Korean]", pattern = "🇰🇷", replacement = "[Korean]"),
                        Rule.RegexReplace(id = id(), label = "🇨🇳/🇹🇼 → [Chinese]", pattern = "🇨🇳|🇹🇼", replacement = "[Chinese]"),
                        Rule.RegexReplace(id = id(), label = "🇫🇷 → [French]", pattern = "🇫🇷", replacement = "[French]"),
                        Rule.RegexReplace(id = id(), label = "🇪🇸 → [Spanish]", pattern = "🇪🇸", replacement = "[Spanish]"),
                        Rule.RegexReplace(id = id(), label = "🇩🇪 → [German]", pattern = "🇩🇪", replacement = "[German]"),
                        Rule.RegexReplace(id = id(), label = "🇮🇹 → [Italian]", pattern = "🇮🇹", replacement = "[Italian]"),
                        Rule.RegexReplace(id = id(), label = "🇧🇷/🇵🇹 → [Portuguese]", pattern = "🇧🇷|🇵🇹", replacement = "[Portuguese]"),
                        Rule.RegexReplace(id = id(), label = "🇷🇺 → [Russian]", pattern = "🇷🇺", replacement = "[Russian]"),
                        Rule.RegexReplace(id = id(), label = "🇹🇭 → [Thai]", pattern = "🇹🇭", replacement = "[Thai]"),
                        Rule.RegexReplace(id = id(), label = "🇻🇳 → [Vietnamese]", pattern = "🇻🇳", replacement = "[Vietnamese]"),
                    ),
                ),
            )

            // 7. Move event/convention tags to the very front: (COMIC…), (C96), (Comiket…), etc.
            //    Matches lrr-preprocess.sh's $event_regex list verbatim.
            add(
                Rule.TagRelocator(
                    id = id(),
                    label = "Event repositioning",
                    pattern = "\\((?:COMIC[^)]*|C\\d+|Comiket[^)]*|COMITIA[^)]*|Reitaisai[^)]*|SPARK[^)]*|GATE[^)]*|Futaket[^)]*|Shuuki[^)]*|Natsu[^)]*|Fuyu[^)]*)\\)",
                    position = Rule.TagRelocator.Position.FRONT,
                    separator = " ",
                )
            )

            // 8. Fix misplaced [Language] at the start of the filename — push it after the title.
            //    Before: "[English] [Artist] Title.cbz"  →  After: "[Artist] Title [English].cbz"
            add(
                Rule.RegexReplace(
                    id = id(),
                    label = "Reposition leading [Language]",
                    pattern = "^\\[($LANG_ALT)\\]\\s*(\\[[^\\]]+\\][^\\[]+?)(\\s*(?:\\[.+)?\\.cbz)$",
                    replacement = "$2 [$1]$3",
                )
            )

            // 9. Ensure a [Language] tag is present at all; if not, append the resolved one.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Append [Language] if absent",
                    condition = Condition(
                        variable = "__filename__",
                        op = Condition.Op.MATCHES,
                        value = "\\[($LANG_ALT)\\]",
                    ),
                    elseRules = listOf(
                        Rule.RegexReplace(
                            id = id(),
                            label = "Insert [%language%] before .cbz",
                            pattern = "(\\.cbz)$",
                            replacement = " [%language%]$1",
                            ignoreCase = true,
                        ),
                    ),
                )
            )

            // 10. Manhwa formatting: insert "Ch %number%" before the language tag, append [Manhwa].
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Manhwa formatting",
                    condition = Condition(
                        variable = "genre",
                        op = Condition.Op.CONTAINS,
                        value = "Manhwa",
                    ),
                    thenRules = listOf(
                        Rule.ConditionalFormat(
                            id = id(),
                            label = "Insert Ch %number% before [Language] (if absent)",
                            // Only add chapter token if the filename doesn't already have one.
                            condition = Condition(
                                variable = "__filename__",
                                op = Condition.Op.MATCHES,
                                value = "\\bCh\\s*\\d+",
                                ignoreCase = true,
                            ),
                            elseRules = listOf(
                                Rule.RegexReplace(
                                    id = id(),
                                    label = "… Ch N [Lang]",
                                    pattern = "\\s*(\\[($LANG_ALT)\\])",
                                    replacement = " Ch %number% $1",
                                ),
                            ),
                        ),
                        Rule.ConditionalFormat(
                            id = id(),
                            label = "Append [Manhwa] (if absent)",
                            condition = Condition(
                                variable = "__filename__",
                                op = Condition.Op.CONTAINS,
                                value = "[Manhwa]",
                            ),
                            elseRules = listOf(
                                Rule.RegexReplace(
                                    id = id(),
                                    label = "Append [Manhwa] before .cbz",
                                    pattern = "(\\.cbz)$",
                                    replacement = " [Manhwa]$1",
                                    ignoreCase = true,
                                ),
                            ),
                        ),
                    ),
                )
            )

            // 11. Collapse whitespace + trim. Must be last filename mutation so
            //     earlier rules' spaces collapse cleanly.
            add(Rule.CleanWhitespace(id = id(), trim = true))

            // 12. Sync ComicInfo.xml — same shape as the original
            //     fix_comicinfo_title + remove_comicinfo_series scripts.
            //     Without the <Title> write LANraragi's auto-extraction
            //     keeps showing the upstream <Title> Mihon embedded
            //     (e.g. 'Chapter 39'); without the <Series> clear
            //     LANraragi groups uploads by Mihon's per-manga <Series>
            //     rather than letting the user organise them themselves.
            //
            //     The Title we write is the human one we just produced
            //     in the filename via the earlier title-fix + manhwa
            //     formatting steps — i.e. "%series%" for normal manga
            //     and "%series% Ch %number%" for manhwa — rather than
            //     the bracket-decorated archive filename. Users can edit
            //     this rule to write additional fields, or delete it
            //     entirely if they prefer ComicInfo untouched.
            add(
                Rule.ConditionalFormat(
                    id = id(),
                    label = "Sync ComicInfo with title",
                    condition = Condition(
                        variable = "genre",
                        op = Condition.Op.CONTAINS,
                        value = "Manhwa",
                    ),
                    thenRules = listOf(
                        Rule.WriteComicInfo(
                            id = id(),
                            label = "Title = series Ch number (manhwa)",
                            fields = mapOf(
                                "Title" to "%series% Ch %number%",
                                "Series" to "",
                            ),
                        ),
                    ),
                    elseRules = listOf(
                        Rule.WriteComicInfo(
                            id = id(),
                            label = "Title = series",
                            fields = mapOf(
                                "Title" to "%series%",
                                "Series" to "",
                            ),
                        ),
                    ),
                ),
            )
        },
    )
}
