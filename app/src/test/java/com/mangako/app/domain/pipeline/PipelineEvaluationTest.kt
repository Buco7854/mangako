package com.mangako.app.domain.pipeline

import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the queryable evaluation API. These are the contract for
 * the headline refactor goal: callers should be able to pass an input
 * and read out any variable's value at any point in the pipeline,
 * without having to re-run the engine to peek at intermediate state.
 */
class PipelineEvaluationTest {

    private val evaluator = PipelineEvaluator()

    @Test fun `final variable lookup returns the last value set during the run`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.SetVariable(id = "1", target = "title", value = "First"),
                Rule.SetVariable(id = "2", target = "title", value = "Second"),
                Rule.SetVariable(id = "3", target = "title", value = "Final"),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz"))
        assertEquals("Final", ev["title"])
    }

    @Test fun `variableAt returns the value the variable held immediately after step N`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.SetVariable(id = "1", target = "title", value = "Step1"),
                Rule.SetVariable(id = "2", target = "title", value = "Step2"),
                Rule.SetVariable(id = "3", target = "title", value = "Step3"),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz"))
        // After step 0 ran, %title% = "Step1".
        assertEquals("Step1", ev.variableAt("title", 0))
        assertEquals("Step2", ev.variableAt("title", 1))
        assertEquals("Step3", ev.variableAt("title", 2))
    }

    @Test fun `variableAt with negative index reads the initial context`() {
        val cfg = PipelineConfig(
            rules = listOf(Rule.SetVariable(id = "1", target = "title", value = "After")),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz", metadata = mapOf("title" to "Before")))
        assertEquals("Before", ev.variableAt("title", -1))
        assertEquals("After", ev["title"])
    }

    @Test fun `contextAt exposes the full state at a specific step`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.SetVariable(id = "1", target = "writer", value = "Author"),
                Rule.SetVariable(id = "2", target = "title", value = "%writer%'s book"),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz"))
        val ctx0 = ev.contextAt(0)
        assertEquals("Author", ctx0.variables["writer"])
        assertNull(ctx0.variables["title"])
        val ctx1 = ev.contextAt(1)
        assertEquals("Author's book", ctx1.variables["title"])
    }

    @Test fun `flat steps include nested children in execution order`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.Group(
                    id = "g",
                    rules = listOf(
                        Rule.SetVariable(id = "n1", target = "a", value = "1"),
                        Rule.SetVariable(id = "n2", target = "b", value = "2"),
                    ),
                ),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz"))
        val flat = ev.flatSteps()
        // Group + 2 nested = 3 entries in the flat list.
        assertEquals(3, flat.size)
        assertEquals("g", flat[0].rule.id)
        assertEquals("n1", flat[1].rule.id)
        assertEquals("n2", flat[2].rule.id)
        // Children inherit depth+1.
        assertEquals(0, flat[0].depth)
        assertEquals(1, flat[1].depth)
        assertEquals(1, flat[2].depth)
    }

    @Test fun `audit steps from evaluation contain monotonically increasing index`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.Group(
                    id = "g",
                    rules = listOf(
                        Rule.SetVariable(id = "n1", target = "a", value = "1"),
                    ),
                ),
                Rule.SetVariable(id = "top", target = "b", value = "2"),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("x.cbz"))
        val audit = ev.toAuditSteps()
        // Group → nested → top-level SetVariable, all uniquely indexed.
        assertEquals(listOf(0, 1, 2), audit.map { it.index })
    }

    @Test fun `before and after contexts for a step expose what changed`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.SetVariable(id = "1", target = "x", value = "old"),
                Rule.SetVariable(id = "2", target = "x", value = "new"),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("a.cbz"))
        val step1 = ev.steps[1]
        assertEquals("old", step1.before.variables["x"])
        assertEquals("new", step1.after.variables["x"])
        assertNotEquals(step1.before.variables, step1.after.variables)
    }

    @Test fun `comicInfoUpdates surface as a side effect on the final context`() {
        val cfg = PipelineConfig(
            rules = listOf(
                Rule.SetVariable(id = "1", target = "title", value = "Hello"),
                Rule.WriteComicInfo(id = "2", fields = mapOf("Title" to "%title%")),
            ),
        )
        val ev = evaluator.evaluate(cfg, PipelineInput("a.cbz"))
        assertEquals("Hello", ev.comicInfoUpdates["Title"])
    }
}
