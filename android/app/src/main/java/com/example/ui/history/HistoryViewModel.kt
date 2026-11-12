package com.example.ui.history

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AudioFiles
import com.example.data.Day
import com.example.data.Dictation
import com.example.data.IvyStore
import com.example.data.Retention
import com.example.data.Rules
import com.example.engine.Engine
import com.example.engine.IvyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HistoryItem(
    val id: String,
    val fullText: String,
    val previewText: String,
    val appName: String,
    val durationText: String,
    val timeFormatted: String,
    val group: HistoryGroup,
    val audio: String? = null,
)

enum class HistoryGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    EARLIER("Earlier")
}

enum class RetentionPeriod(val label: String) {
    ONE_DAY("1 day"),
    SEVEN_DAYS("7 days"),
    THIRTY_DAYS("30 days"),
    FOREVER("Forever")
}

data class HistoryUiState(
    val searchQuery: String = "",
    val items: List<HistoryItem> = emptyList(),
    val selectedItemForDetail: HistoryItem? = null,
    val retentionPeriod: RetentionPeriod = RetentionPeriod.THIRTY_DAYS,
    val showClearAllDialog: Boolean = false,
    val toastMessage: String? = null,
    val playingId: String? = null,
    val retryingId: String? = null,
    val proId: String? = null,
    /** ids whose text was just made professional here: their button shows Undo (original kept in memory) */
    val proDone: Set<String> = emptySet(),
)

/** History from IvyStore (what the bubble typed), grouped by calendar day, kept for the chosen period. */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val ui = MutableStateFlow(HistoryUiState())
    private var player: MediaPlayer? = null

    init {
        IvyStore.init(app)
        IvyStore.purge()
    }

    val uiState: StateFlow<HistoryUiState> = combine(ui, IvyStore.state) { u, s ->
        val now = System.currentTimeMillis()
        val items = s.dictations.map { it.toItem(now) }
        u.copy(
            items = items,
            retentionPeriod = RetentionPeriod.valueOf(s.retention.name),
            selectedItemForDetail = u.selectedItemForDetail?.let { sel -> items.find { it.id == sel.id } },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    private fun Dictation.toItem(now: Long) = HistoryItem(
        id = id,
        fullText = text,
        previewText = if (text.length > 90) text.take(87).trimEnd() + "..." else text,
        appName = app,
        durationText = Rules.duration(durationMs),
        timeFormatted = Rules.timeLabel(at, now, IvyStore.zone),
        group = when (Rules.day(at, now, IvyStore.zone)) {
            Day.TODAY -> HistoryGroup.TODAY
            Day.YESTERDAY -> HistoryGroup.YESTERDAY
            Day.EARLIER -> HistoryGroup.EARLIER
        },
        audio = audio,
    )

    private fun set(f: (HistoryUiState) -> HistoryUiState) { ui.value = f(ui.value) }

    fun onSearchQueryChanged(query: String) = set { it.copy(searchQuery = query) }

    fun onItemSelected(item: HistoryItem) = set { it.copy(selectedItemForDetail = item) }

    fun dismissDetail() {
        stopPlayback()
        set { it.copy(selectedItemForDetail = null) }
    }

    fun setRetentionPeriod(period: RetentionPeriod) {
        IvyStore.setRetention(Retention.valueOf(period.name))
        set { it.copy(toastMessage = "Keeping history for ${period.label.lowercase()}") }
    }

    fun showClearConfirm(show: Boolean) = set { it.copy(showClearAllDialog = show) }

    fun clearAll() {
        stopPlayback()
        IvyStore.clearHistory()
        set { it.copy(showClearAllDialog = false, selectedItemForDetail = null, toastMessage = "History cleared") }
    }

    fun deleteItem(id: String) {
        stopPlayback()
        IvyStore.deleteDictation(id)
        set { it.copy(selectedItemForDetail = null, toastMessage = "Deleted") }
    }

    /** Plays the saved recording; tap again to stop. */
    fun togglePlay(item: HistoryItem) {
        if (ui.value.playingId == item.id) { stopPlayback(); return }
        stopPlayback()
        val ctx = getApplication<Application>()
        if (!AudioFiles.exists(ctx, item.audio)) { showToast("No recording for this one"); return }
        try {
            player = MediaPlayer().apply {
                setDataSource(ctx, Uri.parse(item.audio))
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            set { it.copy(playingId = item.id) }
        } catch (_: Throwable) {
            stopPlayback()
            showToast("Couldn't play this recording")
        }
    }

    private fun stopPlayback() {
        player?.runCatching { stop(); release() }
        player = null
        set { it.copy(playingId = null) }
    }

    /** Runs the saved recording through the model again and replaces the text. */
    fun retry(item: HistoryItem) {
        if (ui.value.retryingId != null) return
        val ctx = getApplication<Application>()
        val uri = item.audio
        if (uri == null || !AudioFiles.exists(ctx, uri)) { showToast("No recording for this one"); return }
        set { it.copy(retryingId = item.id, toastMessage = "Writing it again…") }
        viewModelScope.launch(Dispatchers.Default) {
            val pcm = AudioFiles.read(ctx, uri)
            val h = Engine.acquire(ctx)
            val text = try {
                if (pcm != null && h != 0L) runCatching {
                    val words = IvyStore.state.value.words.toTypedArray()
                    JSONObject(IvyNative.transcribe(h, pcm, pcm.size, words)).getString("text")
                }.getOrNull() else null
            } finally {
                Engine.release()
            }
            val final = text?.let { Rules.applySnippets(com.example.data.TextClean.afterModel(it), IvyStore.state.value.snippets) }
            if (!final.isNullOrBlank()) IvyStore.replaceText(item.id, final)
            set { it.copy(retryingId = null, toastMessage = if (final.isNullOrBlank()) "Couldn't write it again" else "Updated") }
        }
    }

    private val originals = mutableMapOf<String, String>()

    /** Professional button: rewrites the saved text (same on-device model as the bubble chip); Undo puts the original back. */
    fun makeProfessional(item: HistoryItem) {
        if (ui.value.proId != null) return
        val before = originals[item.id]
        if (before != null && item.id in ui.value.proDone) { // Undo
            IvyStore.replaceText(item.id, before)
            originals.remove(item.id)
            set { it.copy(proDone = it.proDone - item.id, toastMessage = "Original restored") }
            return
        }
        val ctx = getApplication<Application>()
        if (com.example.engine.ModelStore.rewrite(ctx) == null) { showToast("Rewrite model not installed"); return }
        set { it.copy(proId = item.id, toastMessage = "Making it professional…") }
        viewModelScope.launch(Dispatchers.Default) {
            // saved words and snippet texts must come back exactly as saved
            val terms = IvyStore.state.value.words + IvyStore.state.value.snippets.map { it.expansion }
            val mk = com.example.data.TextClean.mask(item.fullText, terms)
            val raw = runCatching { Engine.rewrite(ctx, mk.text) }.getOrNull()
            val pro = raw?.takeIf { com.example.data.TextClean.keptAll(it, mk) }?.let { com.example.data.TextClean.unmask(it, mk) }
            val ok = !pro.isNullOrBlank() && pro != item.fullText
            if (ok) { originals[item.id] = item.fullText; IvyStore.replaceText(item.id, pro!!) }
            set { it.copy(proId = null, proDone = if (ok) it.proDone + item.id else it.proDone, toastMessage = if (ok) "Updated" else "Already sounds professional") }
        }
    }

    fun showToast(msg: String) = set { it.copy(toastMessage = msg) }

    fun clearToast() = set { it.copy(toastMessage = null) }

    override fun onCleared() = stopPlayback()
}
