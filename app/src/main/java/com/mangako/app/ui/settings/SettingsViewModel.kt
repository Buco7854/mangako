package com.mangako.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mangako.app.R
import com.mangako.app.data.lanraragi.LanraragiClient
import com.mangako.app.data.settings.SettingsRepository
import com.mangako.app.work.DirectoryScanWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
) : ViewModel() {

    data class TestResult(val ok: Boolean, val message: String)

    val settings: StateFlow<SettingsRepository.Settings> = repo.flow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.Settings())

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    fun setUrl(v: String) = viewModelScope.launch { repo.update { it.copy(lanraragiUrl = v) } }
    fun setApiKey(v: String) = viewModelScope.launch { repo.update { it.copy(lanraragiApiKey = v) } }
    fun setDebounce(v: Int) = viewModelScope.launch { repo.update { it.copy(debounceSeconds = v.coerceIn(1, 60)) } }
    fun setDeleteOnSuccess(v: Boolean) = viewModelScope.launch { repo.update { it.copy(deleteOnSuccess = v) } }
    fun setMode(v: SettingsRepository.Mode) = viewModelScope.launch { repo.update { it.copy(mode = v) } }
    fun setNotify(v: Boolean) = viewModelScope.launch { repo.update { it.copy(notifyOnDetected = v) } }

    fun setWatcherEnabled(v: Boolean) = viewModelScope.launch {
        repo.update { it.copy(watcherEnabled = v) }
        reschedule()
    }

    /**
     * Adds the folder and, if this is the very first watched folder, also
     * enables the background watcher so the user's first download Just
     * Works. Adding a folder without the watcher running is the most common
     * 'why didn't anything happen?' trap; opting them in here matches the
     * implicit intent.
     *
     * Always reschedules — the observer needs the latest URI list as
     * content-uri triggers, so adding a new folder must re-arm the observer
     * with the wider set.
     */
    fun addFolder(uri: String) = viewModelScope.launch {
        val before = repo.flow.first()
        repo.addWatchFolder(uri)
        if (before.watchFolderUris.isEmpty() && !before.watcherEnabled) {
            repo.update { it.copy(watcherEnabled = true) }
        }
        reschedule()
    }

    /**
     * Removes [uri] from the watch list and releases the persistable URI
     * permission back to the OS. Without the release, each add/remove cycle
     * eats one slot against the system's 512-URI per-app limit.
     */
    fun removeFolder(uri: String) = viewModelScope.launch {
        repo.removeWatchFolder(uri)
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        // The content-uri observer was armed for the old URI set; re-arm with
        // what's left, or cancel entirely if nothing remains to watch.
        reschedule()
    }

    /**
     * Re-applies the current settings to the WorkManager schedule. Centralised
     * so every settings mutation that affects watching (toggle, add, remove)
     * uses the same logic.
     */
    private suspend fun reschedule() {
        val settings = repo.flow.first()
        if (settings.watcherEnabled && settings.watchFolderUris.isNotEmpty()) {
            DirectoryScanWorker.schedule(context, settings.watchFolderUris)
        } else {
            DirectoryScanWorker.cancel(context)
        }
    }
    fun scanNow() = DirectoryScanWorker.runOnce(context)

    fun testConnection() = viewModelScope.launch {
        val s = settings.value
        if (s.lanraragiUrl.isBlank()) {
            _testResult.value = TestResult(false, context.getString(R.string.settings_test_url_empty))
            return@launch
        }
        val res = LanraragiClient(s.lanraragiUrl, s.lanraragiApiKey).ping()
        _testResult.value = res.fold(
            onSuccess = { code -> TestResult(code in 200..299, context.getString(R.string.settings_test_http, code)) },
            onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) },
        )
    }

    fun consumeTestResult() { _testResult.value = null }
}
