package com.mangako.app.domain.rule

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Canonical envelope for exporting/importing a pipeline. We keep a schema
 * version so we can migrate older shared configs forward without breaking them.
 */
@Serializable
data class PipelineConfig(
    val schema: Int = SCHEMA_VERSION,
    val name: String = "Mangako Pipeline",
    val rules: List<Rule> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

object RuleJson {
    /**
     * Indented JSON is what we hand to the user for sharing. `ignoreUnknownKeys`
     * lets us evolve the schema without bricking old exports; `classDiscriminator`
     * stays the default ("type") so the sealed hierarchy round-trips.
     */
    val pretty: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    val compact: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(config: PipelineConfig): String = pretty.encodeToString(config)

    fun import(input: String): Result<PipelineConfig> = runCatching {
        val config = pretty.decodeFromString(PipelineConfig.serializer(), input.trim())
        require(config.schema <= PipelineConfig.SCHEMA_VERSION) {
            "Pipeline schema ${config.schema} is newer than this app supports (${PipelineConfig.SCHEMA_VERSION}). Update Mangako."
        }
        config
    }
}
