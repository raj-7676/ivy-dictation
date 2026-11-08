package com.example.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.service.ModelDownloadService
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
        _uiState.update { it.copy(isDownloading = true, downloadError = "") }
        ModelDownloadService.start(getApplication())
    }

    /** The download runs in ModelDownloadService (keeps going in the background); this mirrors its progress. */
    init {
        viewModelScope.launch {
            ModelDownloadService.state.collect { d ->
                _uiState.update {
                    it.copy(
                        isDownloading = d.running || (it.isDownloading && !d.done && d.error.isEmpty()),
                        isDownloadComplete = d.done || it.isDownloadComplete,
                        downloadProgressMb = if (d.done) it.totalModelMb else maxOf(it.downloadProgressMb, (d.fraction * it.totalModelMb).toInt()),
                        downloadError = d.error,
                    )
                }
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
