package com.example.ui.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.IvyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class DictionarySegment {
    DICTIONARY,
    SNIPPETS
}

data class SnippetItem(
    val id: String,
    val triggerPhrase: String,
    val expansionText: String
)

data class DictionaryUiState(
    val activeSegment: DictionarySegment = DictionarySegment.DICTIONARY,
    val personalWords: List<String> = emptyList(),
    val snippets: List<SnippetItem> = emptyList(),
    val newWordInput: String = "",
    val editingSnippet: SnippetItem? = null,
    val showAddSnippetDialog: Boolean = false,
    val statusFeedback: String? = null
)

/** Words Ivy should know and snippets, saved in IvyStore; the bubble uses both on every dictation. */
class DictionaryViewModel(app: Application) : AndroidViewModel(app) {
    private val ui = MutableStateFlow(DictionaryUiState())

    init { IvyStore.init(app) }

    val uiState: StateFlow<DictionaryUiState> = combine(ui, IvyStore.state) { u, s ->
        u.copy(personalWords = s.words, snippets = s.snippets.map { SnippetItem(it.id, it.trigger, it.expansion) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DictionaryUiState())

    private fun set(f: (DictionaryUiState) -> DictionaryUiState) { ui.value = f(ui.value) }

    fun selectSegment(segment: DictionarySegment) = set { it.copy(activeSegment = segment) }

    fun onNewWordInputChanged(text: String) = set { it.copy(newWordInput = text) }

    fun addPersonalWord() {
        val word = ui.value.newWordInput.trim()
        if (word.isBlank()) return
        val added = IvyStore.addWord(word)
        set { it.copy(newWordInput = "", statusFeedback = if (added) "Added '$word'" else "'$word' is already there") }
    }

    fun removePersonalWord(word: String) {
        IvyStore.removeWord(word)
        set { it.copy(statusFeedback = "Removed '$word'") }
    }

    fun showAddSnippet(show: Boolean) = set { it.copy(showAddSnippetDialog = show) }

    fun startEditSnippet(snippet: SnippetItem) = set { it.copy(editingSnippet = snippet) }

    fun cancelEditSnippet() = set { it.copy(editingSnippet = null) }

    fun saveSnippet(id: String?, trigger: String, expansion: String) {
        if (trigger.isBlank() || expansion.isBlank()) return
        IvyStore.saveSnippet(id, trigger.trim(), expansion.trim())
        set {
            it.copy(showAddSnippetDialog = false, editingSnippet = null,
                statusFeedback = if (id == null) "Created snippet '${trigger.trim()}'" else "Updated snippet")
        }
    }

    fun deleteSnippet(id: String) {
        IvyStore.deleteSnippet(id)
        set { it.copy(editingSnippet = null, statusFeedback = "Snippet deleted") }
    }

    fun clearFeedback() = set { it.copy(statusFeedback = null) }
}
