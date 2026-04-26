package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.Condition
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sanity tests for the core pipeline. These are the cheapest guardrail against
 * the rule engine silently regressing — each rule type + the variable plumbing
 * has one focused scenario.
 */
class PipelineExecutorTest {

    private val executor = PipelineExecutor()

    @Test fun `regex replace substitutes %var% tokens`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.RegexReplace(
                    id = "1",
                    pattern = "^file",
                    replacement = "%series%",
                ),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("file.cbz", metadata = mapOf("series" to "My Series")))
        assertEquals("My Series.cbz", out.finalFilename)
    }

    @Test fun `regex replace escapes literal dollar signs from interpolated values`() {
        // Writer name "$1" should not be mistaken for a capture-group back-ref.
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.RegexReplace(id = "1", pattern = "x", replacement = "%writer%"),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("x.cbz", metadata = mapOf("writer" to "\$1")))
        assertEquals("\$1.cbz", out.finalFilename)
    }

    @Test fun `extract regex pulls language from summary into target var`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.ExtractRegex(
                    id = "1",
                    source = "summary",
                    target = "language",
                    pattern = "Languages?:\\s*(English|Japanese)",
                    group = 1,
                ),
                Rule.StringAppend(id = "2", text = " [%language%]"),
            ),
        )
        val out = executor.run(
            cfg,
            PipelineExecutor.Input("chap.cbz", metadata = mapOf("summary" to "Language: Japanese\nArtist: foo")),
        )
        assertEquals("chap.cbz [Japanese]", out.finalFilename)
        assertEquals("Japanese", out.variables["language"])
    }

    @Test fun `extract regex respects onlyIfEmpty`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.ExtractRegex(
                    id = "1",
                    source = "summary",
                    target = "language",
                    pattern = "(\\w+)",
                    onlyIfEmpty = true,
                ),
            ),
        )
        val out = executor.run(
            cfg,
            PipelineExecutor.Input(
                "chap.cbz",
                metadata = mapOf("summary" to "Japanese", "language" to "English"),
            ),
        )
        assertEquals("English", out.variables["language"]) // unchanged
    }

    @Test fun `tag relocator moves event to front`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.TagRelocator(
                    id = "1",
                    pattern = "\\(C\\d+\\)",
                    position = Rule.TagRelocator.Position.FRONT,
                ),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("[Artist] Title (C96) [English].cbz"))
        assertEquals("(C96) [Artist] Title [English].cbz", out.finalFilename)
    }

    @Test fun `conditional else branch runs when condition is false`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.ConditionalFormat(
                    id = "1",
                    condition = Condition("genre", Condition.Op.CONTAINS, "Manhwa"),
                    thenRules = listOf(Rule.StringAppend(id = "t", text = " [Manhwa]")),
                    elseRules = listOf(Rule.StringAppend(id = "e", text = " [Manga]")),
                ),
            ),
        )
        val outManhwa = executor.run(
            cfg,
            PipelineExecutor.Input("a.cbz", metadata = mapOf("genre" to "Manhwa; Action")),
        )
        val outOther = executor.run(
            cfg,
            PipelineExecutor.Input("a.cbz", metadata = mapOf("genre" to "Action")),
        )
        assertEquals("a.cbz [Manhwa]", outManhwa.finalFilename)
        assertEquals("a.cbz [Manga]", outOther.finalFilename)
    }

    @Test fun `audit steps record nesting depth`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.ConditionalFormat(
                    id = "1",
                    condition = Condition("__filename__", Condition.Op.IS_NOT_EMPTY, ""),
                    thenRules = listOf(Rule.StringAppend(id = "t", text = "!")),
                ),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("a.cbz"))
        // Parent is depth 0; nested sub-step is depth 1.
        assertEquals(0, out.steps.first().depth)
        assertEquals(1, out.steps.last().depth)
    }

    @Test fun `catastrophic regex is abandoned within the timeout budget`() {
        // (.*a){25} against 25 a's is a polynomial-blowup ReDoS pattern that
        // JDK 17+ does NOT short-circuit (unlike (a+)+$, which the engine now
        // recognises and handles in linear time). With a 200ms budget the
        // executor should flag it skipped rather than hang.
        val short = PipelineExecutor(ruleTimeoutMs = 200)
        val cfg = PipelineConfig(
            rules = listOf(Rule.RegexReplace(id = "1", pattern = "(.*a){25}", replacement = "x")),
        )
        val started = System.currentTimeMillis()
        val out = short.run(cfg, PipelineExecutor.Input("a".repeat(25)))
        val elapsed = System.currentTimeMillis() - started
        assertTrue("Should return within ~1s; took $elapsed ms", elapsed < 2_000)
        assertTrue(out.steps.first().skipped)
        assertFalse(out.steps.first().skippedReason.isNullOrBlank())
    }

    @Test fun `disabled rule is skipped without mutation`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.StringAppend(id = "1", enabled = false, text = "X"),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("foo.cbz"))
        assertEquals("foo.cbz", out.finalFilename)
        assertTrue(out.steps.first().skipped)
    }

    @Test fun `group runs nested rules in order against the working filename`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.Group(
                    id = "g1",
                    label = "Emoji flags → [Language]",
                    rules = listOf(
                        Rule.RegexReplace(id = "r1", pattern = "🇺🇸|🇬🇧", replacement = "[English]"),
                        Rule.RegexReplace(id = "r2", pattern = "🇯🇵", replacement = "[Japanese]"),
                        Rule.RegexReplace(id = "r3", pattern = "\\s+\\.cbz$", replacement = ".cbz"),
                    ),
                ),
            ),
        )
        val out = executor.run(cfg, PipelineExecutor.Input("[Artist] Title 🇯🇵 .cbz"))
        assertEquals("[Artist] Title [Japanese].cbz", out.finalFilename)
    }

    @Test fun `WriteComicInfo records interpolated field updates as a side effect`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.StringPrepend(id = "p", text = "[Author] "),
                Rule.WriteComicInfo(
                    id = "wci",
                    fields = mapOf(
                        "Title" to "%__filename_stem__%",
                        "Series" to "%series%",
                    ),
                ),
            ),
        )
        val out = executor.run(
            cfg,
            PipelineExecutor.Input("Chapter 39.cbz", metadata = mapOf("series" to "My Series")),
        )
        // Filename mutation runs as usual; WriteComicInfo doesn't touch it.
        assertEquals("[Author] Chapter 39.cbz", out.finalFilename)
        // %__filename_stem__% resolves to the working filename minus ".cbz".
        assertEquals("[Author] Chapter 39", out.comicInfoUpdates["Title"])
        // %series% resolves from the input metadata.
        assertEquals("My Series", out.comicInfoUpdates["Series"])
    }

    @Test fun `clean whitespace collapses repeated spaces and trims`() {
        val cfg = PipelineConfig(rules = listOf(Rule.CleanWhitespace(id = "1", trim = true)))
        val out = executor.run(cfg, PipelineExecutor.Input("   foo    bar   .cbz  "))
        assertEquals("foo bar .cbz", out.finalFilename)
    }
}
