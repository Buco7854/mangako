package com.mangako.app.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mangako.app.data.secrets.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "mangako_settings")

/**
 * Server + watcher preferences. The pipeline itself is stored separately in
 * [com.mangako.app.data.pipeline.PipelineRepository].
 *
 * The LANraragi API key is stored ciphered via [SecretStore] — the DataStore
 * file only ever sees an AES-GCM envelope encrypted with a key held in the
 * Android Keystore. Backups, ADB dumps, and filesystem inspection all see
 * opaque Base64.
 *
 * Processing modes:
 *   - auto       — detected files go straight to the pipeline + upload
 *   - approval   — detected files land in the pending queue; user taps
 *                  "Process" or "Ignore" (in-app or via notification)
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val context: Context,
    private val secrets: SecretStore,
) {

    enum class Mode { AUTO, APPROVAL }

    data class Settings(
        val watchFolderUris: Set<String> = emptySet(),
        val lanraragiUrl: String = "",
        val lanraragiApiKey: String = "",
        val debounceSeconds: Int = 2,
        val deleteOnSuccess: Boolean = true,
        val watcherEnabled: Boolean = false,
        val mode: Mode = Mode.APPROVAL,
        val notifyOnDetected: Boolean = true,
    )

    val flow: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(block: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val next = block(prefs.toSettings())
            prefs[KEY_WATCH_SET] = next.watchFolderUris
            prefs[KEY_URL] = next.lanraragiUrl
            prefs[KEY_API_KEY_ENC] = secrets.encrypt(next.lanraragiApiKey)
            prefs[KEY_DEBOUNCE] = next.debounceSeconds
            prefs[KEY_DELETE] = next.deleteOnSuccess
            prefs[KEY_ENABLED] = next.watcherEnabled
            prefs[KEY_MODE] = next.mode.name
            prefs[KEY_NOTIFY] = next.notifyOnDetected
        }
    }

    suspend fun addWatchFolder(uri: String) = update { s ->
        s.copy(watchFolderUris = s.watchFolderUris + uri)
    }

    suspend fun removeWatchFolder(uri: String) = update { s ->
        s.copy(watchFolderUris = s.watchFolderUris - uri)
    }

    private fun Preferences.toSettings(): Settings {
        return Settings(
            watchFolderUris = this[KEY_WATCH_SET] ?: emptySet(),
            lanraragiUrl = this[KEY_URL].orEmpty(),
            lanraragiApiKey = this[KEY_API_KEY_ENC]?.let(secrets::decrypt).orEmpty(),
            debounceSeconds = this[KEY_DEBOUNCE] ?: 2,
            deleteOnSuccess = this[KEY_DELETE] ?: true,
            watcherEnabled = this[KEY_ENABLED] ?: false,
            mode = this[KEY_MODE]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.APPROVAL,
            notifyOnDetected = this[KEY_NOTIFY] ?: true,
        )
    }

    companion object {
        private val KEY_WATCH_SET = stringSetPreferencesKey("watch_folder_uris")
        private val KEY_URL = stringPreferencesKey("lanraragi_url")
        private val KEY_API_KEY_ENC = stringPreferencesKey("lanraragi_api_key_enc")
        private val KEY_DEBOUNCE = intPreferencesKey("debounce_seconds")
        private val KEY_DELETE = booleanPreferencesKey("delete_on_success")
        private val KEY_ENABLED = booleanPreferencesKey("watcher_enabled")
        private val KEY_MODE = stringPreferencesKey("mode")
        private val KEY_NOTIFY = booleanPreferencesKey("notify_on_detected")
    }
}
