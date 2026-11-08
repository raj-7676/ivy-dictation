package com.example.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.IvyStore
import com.example.data.Rules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class DictationPreview(
    val id: String,
    val previewText: String,
    val targetApp: String,
    val durationText: String,
    val timeAgo: String
)

data class HomeUiState(
    val greeting: String = "Hello",
    val subline: String = "Ready when you are.",
    val isModelReady: Boolean = true,
    val statusText: String = "Ready · offline",
    val wordsDictated: Int = 0,
    val wordsPerMinute: Int = 0,
    val dayStreak: Int = 0,
    val recentDictations: List<DictationPreview> = emptyList(),
)

/** A greeting that fits the time of day, or a casual one, picked fresh every time the app opens. */
private fun pickGreeting(hour: Int = java.time.LocalTime.now().hour): String {
    val timed = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Up late?"
    }
    return listOf(timed, timed, "Hello there", "Hi there", "Welcome back", "Hey").random()
}

private val SUBLINES = listOf(
    "Ready when you are.",
    "Just speak. Ivy types.",
    "What's on your mind?",
    "Say it once, the way you mean it.",
    "Your words, kept on this phone."
)

/** Home numbers from real History: total words, speaking speed, days in a row, the last three dictations. */
class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val greeting = pickGreeting()
    private val subline = SUBLINES.random()

    init { IvyStore.init(app) }

    // "2m ago" must keep ticking while the screen is open, not only when something is dictated
    private val tick = kotlinx.coroutines.flow.flow { while (true) { emit(Unit); kotlinx.coroutines.delay(30_000) } }

    val uiState: StateFlow<HomeUiState> = kotlinx.coroutines.flow.combine(IvyStore.state, tick) { s, _ ->
        val now = System.currentTimeMillis()
        val st = Rules.lifetimeStats(s.lifetime, now, IvyStore.zone)
        HomeUiState(
            greeting = greeting,
            subline = subline,
            wordsDictated = st.words,
            wordsPerMinute = st.wpm,
            dayStreak = st.streak,
            recentDictations = s.dictations.take(3).map {
                DictationPreview(it.id, it.text, it.app, Rules.duration(it.durationMs), Rules.timeAgo(it.at, now, IvyStore.zone))
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(greeting = greeting, subline = subline))
}
