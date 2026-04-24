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
    override val defaultValue: PipelineConfig = PipelineConfig(rules = emptyList())

    override suspend fun readFrom(input: InputStream): PipelineConfig = try {
        RuleJson.compact.decodeFromString(PipelineConfig.serializer(), input.bufferedReader().readText())
    } catch (t: Throwable) {
        // If the stored pipeline became unreadable (schema drift, corruption),
        // we fall back to an empty config so the app still boots. But we MUST
        // leave a loud breadcrumb — silently wiping the user's rules was the
        // previous behavior and it's unacceptable.
        Log.e(TAG, "Failed to deserialize pipeline; falling back to empty config", t)
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
