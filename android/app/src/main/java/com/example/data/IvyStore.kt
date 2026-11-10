package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.util.UUID

data class StoreState(
    val dictations: List<Dictation> = emptyList(), // newest first
    val words: List<String> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val retention: Retention = Retention.THIRTY_DAYS,
    val lifetime: Lifetime = Lifetime(),
)

/** Stats that never depend on History: words dictated, total speaking time, and every day with a dictation. */
data class Lifetime(val words: Int = 0, val ms: Long = 0, val days: List<Long> = emptyList())

/**
 * Everything Ivy remembers, in one small JSON file in the app's private storage (files/ivy_store.json):
 * History, Dictionary words, Snippets and the History retention. The app screens and the bubble service share it.
 * Audio files live in the phone's Recordings/Ivy Dictation folder (AudioFiles) and are removed with their entry.
 */
object IvyStore {
    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()
    private var file: File? = null
    private var app: Context? = null
    val zone: ZoneId get() = ZoneId.systemDefault()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        app = ctx.applicationContext
        file = File(ctx.applicationContext.filesDir, "ivy_store.json")
        _state.value = load()
        purge()
    }

    /** Drops History entries older than the retention period (and their audio). Run on open and on every save. */
    @Synchronized
    fun purge(now: Long = System.currentTimeMillis()) {
        val s = _state.value
        val keep = Rules.purge(s.dictations, s.retention, now)
        if (keep.size == s.dictations.size) return
        val gone = s.dictations.filter { it !in keep }
        update(s.copy(dictations = keep))
        app?.let { ctx -> gone.forEach { AudioFiles.delete(ctx, it.audio) } }
    }

    /** [counted] = false: a recording kept without its text (it could not be written): History only, the Home stats stay. */
    @Synchronized
    fun addDictation(text: String, appName: String, durationMs: Long, audio: String?, at: Long = System.currentTimeMillis(), counted: Boolean = true) {
        val d = Dictation(UUID.randomUUID().toString(), text, appName, durationMs, at, audio)
        update(_state.value.let {
            val day = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toEpochDay()
            val lt = it.lifetime
            it.copy(
                dictations = listOf(d) + it.dictations,
                lifetime = if (!counted) lt else
                    Lifetime(lt.words + Rules.words(text), lt.ms + durationMs, if (day in lt.days) lt.days else (lt.days + day).sorted()),
            )
        })
        purge(at)
    }

    @Synchronized
    fun replaceText(id: String, text: String) =
        update(_state.value.let { s -> s.copy(dictations = s.dictations.map { if (it.id == id) it.copy(text = text) else it }) })

    @Synchronized
    fun deleteDictation(id: String) {
        val s = _state.value
        val d = s.dictations.find { it.id == id } ?: return
        update(s.copy(dictations = s.dictations - d))
        app?.let { AudioFiles.delete(it, d.audio) }
    }

    @Synchronized
    fun clearHistory() {
        val s = _state.value
        update(s.copy(dictations = emptyList()))
        app?.let { ctx -> s.dictations.forEach { AudioFiles.delete(ctx, it.audio) } }
    }

    @Synchronized
    fun setRetention(r: Retention) {
        update(_state.value.copy(retention = r))
        purge()
    }

    @Synchronized
    fun addWord(w: String): Boolean {
        val word = w.trim()
        val s = _state.value
        if (word.isEmpty() || s.words.any { it.equals(word, ignoreCase = true) }) return false
        update(s.copy(words = s.words + word))
        return true
    }

    @Synchronized
    fun removeWord(w: String) = update(_state.value.let { it.copy(words = it.words - w) })

    @Synchronized
    fun saveSnippet(id: String?, trigger: String, expansion: String) {
        val s = _state.value
        val sn = Snippet(id ?: UUID.randomUUID().toString(), trigger.trim(), expansion)
        update(s.copy(snippets = if (id == null) s.snippets + sn else s.snippets.map { if (it.id == id) sn else it }))
    }

    @Synchronized
    fun deleteSnippet(id: String) = update(_state.value.let { s -> s.copy(snippets = s.snippets.filterNot { it.id == id }) })

    private fun update(s: StoreState) {
        _state.value = s
        save(s)
    }

    private fun save(s: StoreState) {
        val f = file ?: return
        val o = JSONObject()
            .put("retention", s.retention.name)
            .put("lifetime", JSONObject().put("words", s.lifetime.words).put("ms", s.lifetime.ms).put("days", JSONArray(s.lifetime.days)))
            .put("words", JSONArray(s.words))
            .put("snippets", JSONArray(s.snippets.map {
                JSONObject().put("id", it.id).put("trigger", it.trigger).put("expansion", it.expansion)
            }))
            .put("dictations", JSONArray(s.dictations.map {
                JSONObject().put("id", it.id).put("text", it.text).put("app", it.app)
                    .put("ms", it.durationMs).put("at", it.at).put("audio", it.audio ?: "")
            }))
        val tmp = File(f.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f) // whole file at once, so a crash never leaves half a history
    }

    private fun load(): StoreState {
        val f = file ?: return StoreState()
        if (!f.exists()) return StoreState()
        return try {
            val o = JSONObject(f.readText())
            fun <T> JSONArray.list(fn: (Int) -> T) = (0 until length()).map(fn)
            val dictations = o.optJSONArray("dictations")?.let { a ->
                a.list {
                    a.getJSONObject(it).let { j ->
                        Dictation(j.getString("id"), j.getString("text"), j.getString("app"), j.getLong("ms"),
                            j.getLong("at"), j.optString("audio").ifEmpty { null })
                    }
                }
            } ?: emptyList()
            val lifetime = o.optJSONObject("lifetime")?.let { l ->
                Lifetime(l.optInt("words"), l.optLong("ms"), l.optJSONArray("days")?.let { a -> a.list { a.getLong(it) } } ?: emptyList())
            } ?: Lifetime( // first run of this version: start from what History already holds
                dictations.sumOf { Rules.words(it.text) }, dictations.sumOf { it.durationMs },
                dictations.map { Rules.date(it.at, zone).toEpochDay() }.distinct().sorted())
            StoreState(
                lifetime = lifetime,
                retention = runCatching { Retention.valueOf(o.getString("retention")) }.getOrDefault(Retention.THIRTY_DAYS),
                words = o.optJSONArray("words")?.let { a -> a.list { a.getString(it) } } ?: emptyList(),
                snippets = o.optJSONArray("snippets")?.let { a ->
                    a.list { a.getJSONObject(it).let { j -> Snippet(j.getString("id"), j.getString("trigger"), j.getString("expansion")) } }
                } ?: emptyList(),
                dictations = dictations,
            )
        } catch (_: Throwable) {
            StoreState()
        }
    }
}
