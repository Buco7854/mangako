package com.mangako.app.domain.rule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization round-trip + schema tests. Exercises every Rule subclass via
 * the LANraragi default template, so adding a new rule type without teaching
 * kotlinx-serialization about it will fail here.
 */
class PipelineJsonTest {

    @Test fun `default template round-trips through export then import`() {
        val original = DefaultTemplate.lanraragiStandard()
        val json = RuleJson.export(original)
        val decoded = RuleJson.import(json).getOrThrow()
        assertEquals(original.name, decoded.name)
        assertEquals(original.rules.size, decoded.rules.size)
        original.rules.zip(decoded.rules).forEach { (a, b) ->
            assertEquals(a::class, b::class)
            assertEquals(a.id, b.id)
        }
    }

    @Test fun `import rejects a schema version newer than this build supports`() {
        val fromFuture = """{"schema": 999, "name": "x", "rules": []}"""
        val result = RuleJson.import(fromFuture)
        assertTrue(result.isFailure)
    }

    @Test fun `import tolerates unknown fields`() {
        val withExtra = """
            {"schema":1,"name":"x","rules":[
              {"type":"clean_whitespace","id":"a","trim":true,"future_field":"ignored"}
            ]}
        """.trimIndent()
        val config = RuleJson.import(withExtra).getOrThrow()
        assertEquals(1, config.rules.size)
    }
}
