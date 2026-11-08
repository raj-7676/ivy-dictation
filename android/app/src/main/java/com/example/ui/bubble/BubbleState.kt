package com.example.ui.bubble

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.components.performTactileFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * All distinct states supported by the floating Ivy bubble.
 */
enum class BubbleMode {
    IDLE,
    RECORDING,
    TRANSCRIBING,
    PASTED,
    FALLBACK_COPIED,
    FALLBACK_NO_SPEECH,
    FALLBACK_ERROR,
    FALLBACK_NOTHING_TO_FIX
}

enum class BubbleDockSide {
    LEFT,
    RIGHT
}

enum class BubbleSize(val diameterDp: Dp) {
    SMALL(40.dp),
    MEDIUM(46.dp),
    LARGE(54.dp)
}

/**
 * Common misspellings for the Touch Up typo-only spell check engine.
 * Specifically checks for typos only (not capitalization or punctuation).
 */
val CommonTyposMap = mapOf(
    "recieve" to "receive",
    "recieved" to "received",
    "definately" to "definitely",
    "seperate" to "separate",
    "seperated" to "separated",
    "occured" to "occurred",
    "truely" to "truly",
    "untill" to "until",
    "alot" to "a lot",
    "neccessary" to "necessary",
    "tommorow" to "tomorrow",
    "goverment" to "government",
    "calender" to "calendar",
    "wierd" to "weird",
    "accomodate" to "accommodate"
)

/** The real dictation behind the bubble (the accessibility service). Without one, the bubble runs its demo. */
interface BubbleEngine {
    /** Starts listening. False = couldn't (the bubble shows the error pill). */
    fun start(): Boolean
    /** Stops listening and returns the text ("" = nothing heard, null = failed). Called off the main thread. */
    fun finish(): String?
    fun cancel()
    /** Mic loudness 0..1. */
    fun level(): Float
}

/**
 * Standalone state holder for the BubbleOverlay.
 * Decoupled from screen navigation so it can later be hosted in an Android overlay Service.
 */
@Stable
class BubbleState(
    val context: Context,
    val coroutineScope: CoroutineScope
) {
    var mode by mutableStateOf(BubbleMode.IDLE)
        private set

    var dockSide by mutableStateOf(BubbleDockSide.RIGHT)
    var bubbleSize by mutableStateOf(BubbleSize.MEDIUM)
    var bubbleOpacity by mutableFloatStateOf(0.92f)
    var showOnlyWhenKeyboardOpen by mutableStateOf(true)

    // Drag offset relative to default docked position
    var dragOffset by mutableStateOf(Offset.Zero)

    /** Called when the user lets go of a drag (the floating window snaps to the nearest side). */
    var onDragEnd: (() -> Unit)? = null

    // Live audio waveform simulation data (normalized 0f..1f heights)
    val waveformAmplitudes = mutableStateListOf<Float>().apply {
        repeat(12) { add(0.2f) }
    }

    // Recording timer in seconds
    var recordingDurationSeconds by mutableIntStateOf(0)
        private set

    // Touch Up state for typo corrections
    var touchUpStatus by mutableStateOf<String?>(null)
        private set
    var touchUpFixedCount by mutableIntStateOf(0)
        private set

    // Sample phrases to cycle through during dictation demo
    private val sampleDictations = listOf(
        "Hey Alex, I'll send the updated pitch deck to your email tommorow morning.",
        "Could you please check if we can accomodate the client meeting at 3 PM?",
        "I will definately review the code changes and recieve your pull request today."
    )
    private var dictationIndex = 0

    private var recordingJob: Job? = null
    private var isHoldToTalkActive = false

    /** Small chip above the bubble after a dictation ("Make it professional?", then "Undo"); null = hidden. */
    var chip by mutableStateOf<String?>(null)
    /** Tap on the idle bubble opens this 3-option menu: History / Record / Cancel (Cancel only works while busy). */
    var menuOpen by mutableStateOf(false)
    var onOpenHistory: (() -> Unit)? = null
    var onChip: (() -> Unit)? = null
    /** The tone slider shown after a dictation: Original | Grammar | Formal. */
    var toneVisible by mutableStateOf(false)
    var toneLevel by mutableIntStateOf(0)
    var toneBusy by mutableStateOf(false)
    var toneStops by mutableStateOf(listOf("Original", "Grammar", "Formal"))
    var onTone: ((Int) -> Unit)? = null
    /** True for 60 s after a dictation: the tap menu then offers "Polish last". */
    var polishReady by mutableStateOf(false)
    var onPolishLast: (() -> Unit)? = null
    /** Upper chip, above [chip]: (old design, unused). */
    var chip2 by mutableStateOf<String?>(null)
    var onChip2: (() -> Unit)? = null

    /** The service shows the "Writing…" pill while it works on something else (the professional rewrite). */
    /** Only ever touches the state it owns: a recording the user started meanwhile must not be knocked back to idle. */
    fun busy(on: Boolean) {
        if (on) { if (mode == BubbleMode.IDLE) mode = BubbleMode.TRANSCRIBING }
        else if (mode == BubbleMode.TRANSCRIBING) mode = BubbleMode.IDLE
    }

    /** Set by the overlay service; null = demo mode (sandbox screen). */
    var engine: BubbleEngine? = null

    fun startRecording(isHoldToTalk: Boolean = false) {
        if (mode == BubbleMode.RECORDING) return
        val eng = engine
        if (eng != null && !eng.start()) {
            triggerFallbackState(BubbleMode.FALLBACK_ERROR)
            return
        }
        performTactileFeedback(context, 35)
        click()
        isHoldToTalkActive = isHoldToTalk
        toneVisible = false
        chip2 = null
        chip = null        // an old "Make it professional?" / "Undo" must not hang over a new recording
        menuOpen = false
        mode = BubbleMode.RECORDING
        recordingDurationSeconds = 0
        touchUpStatus = null
        touchUpFixedCount = 0

        recordingJob?.cancel()
        recordingJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive && mode == BubbleMode.RECORDING) {
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                recordingDurationSeconds = elapsed.toInt()

                // Update animated waveform amplitudes (real mic loudness when an engine is attached)
                if (eng != null) {
                    waveformAmplitudes.removeAt(0)
                    waveformAmplitudes.add((0.15f + eng.level() * 2.5f).coerceAtMost(1f))
                } else {
                    for (i in waveformAmplitudes.indices) {
                        val base = 0.2f + Random.nextFloat() * 0.75f
                        waveformAmplitudes[i] = base
                    }
                }
                delay(90)
            }
        }
    }

    fun finishRecording(onTextReady: (String) -> Unit) {
        if (mode != BubbleMode.RECORDING) return
        performTactileFeedback(context, 25)
        recordingJob?.cancel()
        mode = BubbleMode.TRANSCRIBING

        val eng = engine
        if (eng != null) {
            coroutineScope.launch {
                val text = withContext(Dispatchers.Default) { eng.finish() }
                when {
                    text == null -> triggerFallbackState(BubbleMode.FALLBACK_ERROR)
                    text.isBlank() -> triggerFallbackState(BubbleMode.FALLBACK_NO_SPEECH)
                    else -> {
                        mode = BubbleMode.PASTED
                        performTactileFeedback(context, 30)
                        click()
                        onTextReady(text) // may switch to FALLBACK_COPIED if the box refused the text
                        delay(1600)
                        if (mode == BubbleMode.PASTED) mode = BubbleMode.IDLE
                    }
                }
            }
            return
        }
        coroutineScope.launch {
            // Simulated on-device speech model transcription delay (1.5s)
            delay(1500)
            val text = sampleDictations[dictationIndex % sampleDictations.size]
            dictationIndex++

            mode = BubbleMode.PASTED
            performTactileFeedback(context, 30)
            onTextReady(text)

            // Show Touch Up affordance
            touchUpStatus = "Typos? Touch Up"

            // Auto retract to idle after 2.4 seconds
            delay(2400)
            if (mode == BubbleMode.PASTED) {
                mode = BubbleMode.IDLE
            }
        }
    }

    /** Settings > Start & finish click sounds: the phone's own soft key click. */
    private fun click() {
        if (!context.getSharedPreferences("ivy", Context.MODE_PRIVATE).getBoolean("sounds", false)) return
        (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager)
            .playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 0.6f)
    }

    fun cancelRecording() {
        performTactileFeedback(context, 20)
        recordingJob?.cancel()
        engine?.let { e -> coroutineScope.launch(Dispatchers.Default) { e.cancel() } }
        mode = BubbleMode.IDLE
        recordingDurationSeconds = 0
        touchUpStatus = null
    }

    fun onHoldRelease(onTextReady: (String) -> Unit) {
        if (isHoldToTalkActive && mode == BubbleMode.RECORDING) {
            isHoldToTalkActive = false
            finishRecording(onTextReady)
        }
    }

    private var fallbackJob: Job? = null

    fun triggerFallbackState(fallbackMode: BubbleMode) {
        performTactileFeedback(context, 30)
        mode = fallbackMode
        fallbackJob?.cancel() // a newer pill gets its own full time, the older timer must not end it early
        fallbackJob = coroutineScope.launch {
            delay(2800)
            if (mode == fallbackMode) {
                mode = BubbleMode.IDLE
            }
        }
    }

    /**
     * Executes the typo-only spell check on current text without altering punctuation or casing.
     */
    fun performTypoTouchUp(currentText: String, onTextCorrected: (String) -> Unit) {
        performTactileFeedback(context, 40)
        var corrected = currentText
        var fixes = 0

        CommonTyposMap.forEach { (typo, fix) ->
            val regex = Regex("\\b$typo\\b", RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(corrected)) {
                fixes++
                corrected = regex.replace(corrected) { matchResult ->
                    // Preserve original casing of first letter if capitalized
                    if (matchResult.value.first().isUpperCase()) {
                        fix.replaceFirstChar { it.uppercase() }
                    } else {
                        fix
                    }
                }
            }
        }

        touchUpFixedCount = fixes
        if (fixes > 0) {
            touchUpStatus = if (fixes == 1) "1 typo fixed ✓" else "$fixes typos fixed ✓"
            onTextCorrected(corrected)
        } else {
            touchUpStatus = "No typos found ✓"
        }

        coroutineScope.launch {
            delay(2000)
            touchUpStatus = null
        }
    }

    fun resetToIdle() {
        recordingJob?.cancel()
        mode = BubbleMode.IDLE
        touchUpStatus = null
        dragOffset = Offset.Zero
    }
}

@Composable
fun rememberBubbleState(context: Context): BubbleState {
    val coroutineScope = rememberCoroutineScope()
    return remember(context) {
        BubbleState(context = context, coroutineScope = coroutineScope)
    }
}
