package com.mangako.app.data.pipeline

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import com.mangako.app.domain.rule.DefaultTemplate
import com.mangako.app.domain.rule.PipelineConfig
import com.mangako.app.domain.rule.RuleJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PipelineRepository"

/**
 * DataStore-backed persistence for the user's active [PipelineConfig]. We use
 * a typed `DataStore<PipelineConfig>` so every read/write round-trips through
 * kotlinx-serialization — the same format as export/import.
 */

private object PipelineSerializer : Serializer<PipelineConfig> {
    /**
     * Used by DataStore when the persisted pipeline file is absent (fresh
     * install, app data cleared) or it failed to parse. Pre-loading the
     * LANraragi standard template here means a brand-new user sees a
     * working pipeline immediately and the Inbox can simulate a real
     * %title% on day one — instead of staring at a "Add at least one
     * renaming rule" setup banner.
     *
     * The user can still wipe it: clearing every rule from the Pipeline
     * screen writes an empty `rules: []` config to disk, which DataStore
     * then reads back verbatim on subsequent launches (defaultValue is
     * only consulted when the file doesn't exist or won't parse).
     */
    override val defaultValue: PipelineConfig = DefaultTemplate.lanraragiStandard()

    override suspend fun readFrom(input: InputStream): PipelineConfig = try {
        RuleJson.compact.decodeFromString(PipelineConfig.serializer(), input.bufferedReader().readText())
    } catch (t: Throwable) {
        // If the stored pipeline became unreadable (schema drift, corruption),
        // fall back to the standard template rather than an empty config —
        // the user has a chance of recognising their setup and restoring any
        // tweaks, whereas an empty pipeline silently breaks every Inbox
        // simulation. We MUST leave a loud breadcrumb either way — silently
        // wiping the user's rules was the previous behavior and it's
        // unacceptable.
        Log.e(TAG, "Failed to deserialize pipeline; falling back to LANraragi standard template", t)
        defaultValue
    }

    override suspend fun writeTo(t: PipelineConfig, output: OutputStream) {
        output.write(RuleJson.compact.encodeToString(t).toByteArray())
    }
}

private val Context.pipelineStore: DataStore<PipelineConfig> by dataStore(
    fileName = "pipeline.json",
    serializer = PipelineSerializer,
)

@Singleton
class PipelineRepository @Inject constructor(@ApplicationContext private val context: Context) {

    val flow: Flow<PipelineConfig> = context.pipelineStore.data

    suspend fun update(transform: (PipelineConfig) -> PipelineConfig) {
        context.pipelineStore.updateData(transform)
    }

    suspend fun replace(config: PipelineConfig) {
        context.pipelineStore.updateData { config }
    }

    /** Overwrite the pipeline with the opinionated LANraragi defaults. */
    suspend fun loadLanraragiStandard() = replace(DefaultTemplate.lanraragiStandard())

    /** Parse a JSON string into a config and swap it in. Returns the parse result. */
    suspend fun importJson(raw: String): Result<PipelineConfig> {
        val parsed = RuleJson.import(raw)
        parsed.onSuccess { replace(it) }
        return parsed
    }

    suspend fun exportJson(): String = RuleJson.export(flow.first())
}
