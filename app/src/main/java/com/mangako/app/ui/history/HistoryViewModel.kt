package com.mangako.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mangako.app.data.history.HistoryRecord
import com.mangako.app.data.history.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryDetailViewModel @Inject constructor(
    private val repo: HistoryRepository,
) : ViewModel() {

    private val record = MutableStateFlow<HistoryRecord?>(null)
    val state: StateFlow<HistoryRecord?> = record.asStateFlow()

    fun load(id: String) = viewModelScope.launch {
        record.value = repo.find(id)
    }
}
