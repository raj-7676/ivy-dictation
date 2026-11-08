package com.example.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.ModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The setup wizard's steps, in order (Android: no hotkeys; the bubble appears when a text box is tapped). */
enum class SetupStep { WELCOME, HOW_IT_WORKS, POLISH, PERMISSIONS, DOWNLOAD, WORDS, SNIPPETS, PRACTICE }

enum class PracticePhase { IDLE, FOCUSED, LISTENING, WRITING, DONE }

data class OnboardingUiState(
    val step: SetupStep = SetupStep.WELCOME,
    val goingForward: Boolean = true,
    val micGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val accessibilityGranted: Boolean = false,
    val downloadProgressMb: Int = 0,
    val totalModelMb: Int = 1549, // speech model + ear + rewrite model, ~1.5 GB
    val downloadError: String = "",
    val isDownloading: Boolean = false,
    val isDownloadComplete: Boolean = false,
    val practicePhase: PracticePhase = PracticePhase.IDLE,
    val practiceText: String = "",
)

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Replay from Settings starts at the beginning but keeps permissions and the downloaded model. */
    fun restart() = _uiState.update {
        it.copy(step = SetupStep.WELCOME, goingForward = true, practicePhase = PracticePhase.IDLE, practiceText = "")
    }

    fun next() {
        val all = SetupStep.entries
        val i = all.indexOf(_uiState.value.step)
        if (i < all.lastIndex) {
            _uiState.update { it.copy(step = all[i + 1], goingForward = true) }
            if (all[i + 1] == SetupStep.DOWNLOAD) startDownload()
        }
    }

    fun back() {
        val all = SetupStep.entries
        val i = all.indexOf(_uiState.value.step)
        if (i > 0) _uiState.update { it.copy(step = all[i - 1], goingForward = false) }
    }

    /** Real permission state, read from Android whenever the screen comes back (see OnboardingScreen). */
    fun setPermissions(mic: Boolean, overlay: Boolean, accessibility: Boolean) =
        _uiState.update { it.copy(micGranted = mic, overlayGranted = overlay, accessibilityGranted = accessibility) }

    /** Downloads the models once (resumes after a drop); on failure shows the error and the button becomes Retry. */
    fun startDownload() {
        val s = _uiState.value
        if (s.isDownloading || s.isDownloadComplete) return
        val ctx = getApplication<Application>()
        _uiState.update { it.copy(isDownloading = true, downloadError = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ModelStore.install(ctx) { f -> _uiState.update { it.copy(downloadProgressMb = (f * it.totalModelMb).toInt()) } }
                _uiState.update { it.copy(isDownloading = false, isDownloadComplete = true, downloadProgressMb = it.totalModelMb) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isDownloading = false, downloadError = "No connection. Check the internet and tap Retry.") }
            }
        }
    }

    /** Practice: tap the box -> bubble -> listening -> text appears -> done. */
    fun practiceTapBox() {
        if (_uiState.value.practicePhase == PracticePhase.IDLE) _uiState.update { it.copy(practicePhase = PracticePhase.FOCUSED) }
    }

    fun practiceTapBubble() {
        if (_uiState.value.practicePhase != PracticePhase.FOCUSED) return
        _uiState.update { it.copy(practicePhase = PracticePhase.LISTENING) }
        viewModelScope.launch {
            delay(1800)
            _uiState.update { it.copy(practicePhase = PracticePhase.WRITING) }
            val text = "Hey! I'm trying Ivy, and it types exactly what I say."
            for (n in 1..text.length) {
                delay(22)
                _uiState.update { it.copy(practiceText = text.take(n)) }
            }
            _uiState.update { it.copy(practicePhase = PracticePhase.DONE) }
        }
    }
}
