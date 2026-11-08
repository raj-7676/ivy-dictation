package com.example.ui.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.engine.MicRecorder
import com.example.service.Permissions
import com.example.ui.bubble.BubbleDockSide
import com.example.ui.bubble.BubbleSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SettingsUiState(
    // Mic test: 5 s of real microphone level, only when tapped
    val micMeterLevel: Float = 0f,
    val isMicTesting: Boolean = false,

    // Bubble (read live by the bubble service from the "ivy" preferences)
    val bubbleSize: BubbleSize = BubbleSize.MEDIUM,
    val bubbleOpacity: Float = 0.92f,
    val bubbleDockSide: BubbleDockSide = BubbleDockSide.RIGHT,
    val showOnlyWhenKeyboardIsOpen: Boolean = true,

    // Feedback
    val isHapticsEnabled: Boolean = true,
    val isSoundsEnabled: Boolean = false,

    val feedbackMessage: String? = null
)

/** Settings saved in SharedPreferences "ivy"; the bubble service listens and applies changes at once. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("ivy", Context.MODE_PRIVATE)
    private val _uiState = MutableStateFlow(read())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var micTestJob: Job? = null

    private fun read() = SettingsUiState(
        bubbleSize = runCatching { BubbleSize.valueOf(prefs.getString("bubble_size", "MEDIUM")!!) }.getOrDefault(BubbleSize.MEDIUM),
        bubbleOpacity = prefs.getFloat("bubble_opacity", 0.92f),
        bubbleDockSide = if (prefs.getBoolean("bubble_left", false)) BubbleDockSide.LEFT else BubbleDockSide.RIGHT,
        showOnlyWhenKeyboardIsOpen = prefs.getBoolean("keyboard_only", true),
        isHapticsEnabled = prefs.getBoolean("haptics", true),
        isSoundsEnabled = prefs.getBoolean("sounds", false),
    )

    /** Re-read when the screen comes back (the bubble can change its side by dragging). */
    fun refresh() { _uiState.value = read().copy(isMicTesting = _uiState.value.isMicTesting, micMeterLevel = _uiState.value.micMeterLevel) }

    private fun put(f: (android.content.SharedPreferences.Editor) -> Unit, s: SettingsUiState) {
        prefs.edit().also(f).apply()
        _uiState.value = s
    }

    /** Listens for 5 seconds and shows the real level (needs the microphone permission). */
    fun testMic() {
        val ctx = getApplication<Application>()
        if (_uiState.value.isMicTesting) return
        if (!Permissions.mic(ctx)) {
            _uiState.value = _uiState.value.copy(feedbackMessage = "Allow the microphone first (permissions below)")
            return
        }
        micTestJob = viewModelScope.launch(Dispatchers.Default) {
            val mic = MicRecorder()
            _uiState.value = _uiState.value.copy(isMicTesting = true)
            try {
                mic.start()
                repeat(50) {
                    delay(100)
                    _uiState.value = _uiState.value.copy(micMeterLevel = (mic.level * 2.5f).coerceIn(0f, 1f))
                }
            } finally {
                mic.stop()
                _uiState.value = _uiState.value.copy(isMicTesting = false, micMeterLevel = 0f)
            }
        }
    }

    fun setBubbleSize(size: BubbleSize) = put({ it.putString("bubble_size", size.name) }, _uiState.value.copy(bubbleSize = size))

    fun setBubbleOpacity(opacity: Float) = put({ it.putFloat("bubble_opacity", opacity) }, _uiState.value.copy(bubbleOpacity = opacity))

    fun setBubbleSide(side: BubbleDockSide) =
        put({ it.putBoolean("bubble_left", side == BubbleDockSide.LEFT) }, _uiState.value.copy(bubbleDockSide = side))

    fun toggleKeyboardOnly(enabled: Boolean) =
        put({ it.putBoolean("keyboard_only", enabled) }, _uiState.value.copy(showOnlyWhenKeyboardIsOpen = enabled))

    fun toggleHaptics(enabled: Boolean) = put({ it.putBoolean("haptics", enabled) }, _uiState.value.copy(isHapticsEnabled = enabled))

    fun toggleSounds(enabled: Boolean) = put({ it.putBoolean("sounds", enabled) }, _uiState.value.copy(isSoundsEnabled = enabled))

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(feedbackMessage = null)
    }

    override fun onCleared() { micTestJob?.cancel() }
}
