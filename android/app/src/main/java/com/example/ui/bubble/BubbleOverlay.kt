package com.example.ui.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.History
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.IvyLogoMark
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyGreen
import com.example.ui.theme.IvyNearBlack
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.IvyOrangeSoft
import com.example.ui.theme.InterFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Standalone Composable rendering the Ivy Floating Bubble.
 * Decoupled from app navigation so it can easily move into an Android overlay Service.
 */
@Composable
fun BubbleOverlay(
    state: BubbleState,
    onTextEmitted: (String) -> Unit,
    currentHostText: String = "",
    onTextCorrected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true, // false in the floating window: only the bubble itself takes touches
) {
    val bubbleDiameter = state.bubbleSize.diameterDp
    val isExpanded = state.mode != BubbleMode.IDLE

    // Alignment and offset based on dockSide and dragOffset
    Box(
        modifier = (if (fillWidth) modifier.fillMaxWidth() else modifier)
            .padding(horizontal = if (fillWidth) 16.dp else 6.dp),
        contentAlignment = if (state.dockSide == BubbleDockSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
    ) {
        Column(
            horizontalAlignment = if (state.dockSide == BubbleDockSide.LEFT) Alignment.Start else Alignment.End,
            modifier = Modifier
                .offset {
                    // the floating window moves itself instead (see IvyAccessibilityService)
                    if (fillWidth) IntOffset(state.dragOffset.x.roundToInt(), state.dragOffset.y.roundToInt())
                    else IntOffset.Zero
                }
        ) {
            // Tone slider: Original | Grammar | Formal (shown after a dictation)
            AnimatedVisibility(
                visible = state.toneVisible,
                enter = fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                    androidx.compose.animation.slideInVertically(androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 380f)) { it / 2 } +
                    androidx.compose.animation.scaleIn(androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 380f), initialScale = 0.75f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)),
                exit = fadeOut(tween(160)) + androidx.compose.animation.scaleOut(tween(200, easing = FastOutSlowInEasing), targetScale = 0.85f,
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f))
            ) {
                Box(Modifier.padding(bottom = 8.dp)) { ToneSlider(state) }
            }

            // Keep the last label while a chip fades out (no flash of another text)
            var lastChipLabel by remember { mutableStateOf("") }
            (state.chip ?: state.touchUpStatus)?.let { lastChipLabel = it }
            val chipLabel = state.chip ?: state.touchUpStatus ?: lastChipLabel
            var lastChip2Label by remember { mutableStateOf("") }
            state.chip2?.let { lastChip2Label = it }

            // Upper chip ("Fix grammar") sits above the lower one ("Make it professional?" / "Undo")
            AnimatedVisibility(
                visible = state.chip2 != null,
                enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                    androidx.compose.animation.expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(150)) +
                    androidx.compose.animation.shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom)
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IvyNearBlack.copy(alpha = 0.9f))
                        .border(1.dp, IvyOrange.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = IvyOrange.copy(alpha = 0.3f)),
                            onClick = { state.onChip2?.invoke() }
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        text = state.chip2 ?: lastChip2Label,
                        fontFamily = InterFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary
                    )
                }
            }

            // Touch Up Typo Chip (floats above bubble when available)
            AnimatedVisibility(
                visible = state.chip != null || state.touchUpStatus != null,
                enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) +
                    androidx.compose.animation.expandVertically(tween(220, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom),
                exit = fadeOut(tween(150)) +
                    androidx.compose.animation.shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom)
            ) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(IvyNearBlack.copy(alpha = 0.9f))
                        .border(1.dp, IvyOrange.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(color = IvyOrange.copy(alpha = 0.3f)),
                            onClick = {
                                val chipAction = state.onChip
                                if (state.chip != null && chipAction != null) chipAction()
                                else if (onTextCorrected != null) state.performTypoTouchUp(currentHostText, onTextCorrected)
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("touch_up_chip")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "Typo Spell Check",
                            tint = IvyOrange,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = chipLabel,
                            fontFamily = InterFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Tap menu: History / Record / Cancel
            LaunchedEffect(state.menuOpen) { if (state.menuOpen) { kotlinx.coroutines.delay(6000); state.menuOpen = false } }
            LaunchedEffect(state.mode) { if (state.mode != BubbleMode.IDLE) state.menuOpen = false }
            AnimatedVisibility(
                visible = state.menuOpen && state.mode == BubbleMode.IDLE,
                enter = fadeIn(tween(160, easing = FastOutSlowInEasing)) +
                    androidx.compose.animation.expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) +
                    androidx.compose.animation.scaleIn(tween(240, easing = FastOutSlowInEasing), initialScale = 0.6f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (state.dockSide == BubbleDockSide.LEFT) 0f else 1f, 1f)),
                exit = fadeOut(tween(120)) +
                    androidx.compose.animation.shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom) +
                    androidx.compose.animation.scaleOut(tween(180, easing = FastOutSlowInEasing), targetScale = 0.6f,
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (state.dockSide == BubbleDockSide.LEFT) 0f else 1f, 1f))
            ) {
                Column(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(IvyNearBlack.copy(alpha = 0.95f))
                        .border(1.dp, IvyOrange.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(vertical = 4.dp)
                ) {
                    BubbleMenuItem("History", Icons.Default.History, enabled = true, index = 0) {
                        state.menuOpen = false; state.onOpenHistory?.invoke()
                    }
                    BubbleMenuItem("Record", Icons.Default.Mic, enabled = true, index = 1) {
                        state.menuOpen = false; state.startRecording(isHoldToTalk = false)
                    }
                    // nothing to cancel while idle; works once something is recording
                    // a dictation in the last 60 s can be polished again; otherwise there is nothing to cancel while idle
                    if (state.polishReady) BubbleMenuItem("Polish last", Icons.Default.AutoFixHigh, enabled = true, index = 2) {
                        state.menuOpen = false; state.onPolishLast?.invoke()
                    } else BubbleMenuItem("Cancel", Icons.Default.Close, enabled = state.mode != BubbleMode.IDLE, index = 2) {
                        state.cancelRecording()
                    }
                }
            }

            // The main interactive bubble / expanded pill
            Box(
                modifier = Modifier
                    .pointerInput(state.mode) {
                        detectDragGestures(onDragEnd = { state.onDragEnd?.invoke() }) { change, dragAmount ->
                            change.consume()
                            state.dragOffset = Offset(
                                x = (state.dragOffset.x + dragAmount.x).coerceIn(-240f, 240f),
                                y = (state.dragOffset.y + dragAmount.y).coerceIn(-300f, 200f)
                            )
                        }
                    }
                    .alpha(androidx.compose.animation.core.animateFloatAsState(if (state.mode == BubbleMode.IDLE) state.bubbleOpacity else 1f, tween(200), label = "bubble-alpha").value) // pills opaque: page text must not show through
            ) {
                AnimatedContent(
                    targetState = state.mode,
                    // grow / shrink from the docked edge, never from the top-left corner
                    contentAlignment = if (state.dockSide == BubbleDockSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd,
                    // No dip, no flicker: the new shape fades in ON TOP of the old one (which stays until it is covered);
                    // going back to the bubble, the bubble is already there and the pill fades away above it.
                    transitionSpec = {
                        val size = androidx.compose.animation.SizeTransform(clip = false) { _, _ -> androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f) }
                        if (targetState == BubbleMode.IDLE)
                            (androidx.compose.animation.EnterTransition.None togetherWith fadeOut(tween(200, easing = FastOutSlowInEasing)))
                                .apply { targetContentZIndex = -1f }.using(size)
                        else
                            // the old shape stays fully visible while the new one fades in over it, then goes
                            (fadeIn(tween(150, easing = FastOutSlowInEasing)) togetherWith fadeOut(tween(20, delayMillis = 130)))
                                .using(size)
                    },
                    label = "BubbleModeTransition"
                ) { targetMode ->
                    when (targetMode) {
                        BubbleMode.IDLE -> {
                            IdleBubble(
                                diameter = bubbleDiameter,
                                onTap = { state.menuOpen = !state.menuOpen },
                                // hold 2 s: recording starts and keeps going, no need to keep holding
                                onLongPress = { state.menuOpen = false; state.startRecording(isHoldToTalk = false) }
                            )
                        }
                        BubbleMode.RECORDING -> {
                            RecordingPill(
                                durationSeconds = state.recordingDurationSeconds,
                                waveformAmplitudes = state.waveformAmplitudes,
                                onCancel = { state.cancelRecording() },
                                onDone = { state.finishRecording(onTextEmitted) }
                            )
                        }
                        BubbleMode.TRANSCRIBING -> {
                            TranscribingPill()
                        }
                        BubbleMode.PASTED -> {
                            PastedPill()
                        }
                        BubbleMode.FALLBACK_COPIED -> {
                            FallbackPill(
                                text = "Copied — tap to paste",
                                icon = Icons.Default.Check,
                                iconColor = IvyOrangeSoft
                            )
                        }
                        BubbleMode.FALLBACK_NO_SPEECH -> {
                            FallbackPill(
                                text = "Didn't hear anything",
                                icon = Icons.Default.Close,
                                iconColor = TextMuted
                            )
                        }
                        BubbleMode.FALLBACK_NOTHING_TO_FIX -> {
                            FallbackPill(
                                text = "Already good",
                                icon = Icons.Default.Check,
                                iconColor = IvyOrangeSoft
                            )
                        }
                        BubbleMode.FALLBACK_ERROR -> {
                            FallbackPill(
                                text = "Couldn't transcribe · Retry",
                                icon = Icons.Default.Refresh,
                                iconColor = IvyOrange,
                                onClick = { state.startRecording(isHoldToTalk = false) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleBubble(
    diameter: androidx.compose.ui.unit.Dp,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val ring = remember { androidx.compose.animation.core.Animatable(0f) }
    // while the finger is down a ring fills over half a second, so it is clear how long to hold
    LaunchedEffect(pressed) {
        if (pressed) ring.animateTo(1f, tween(HOLD_TO_RECORD_MS.toInt(), easing = LinearEasing)) else ring.snapTo(0f)
    }
    val squish by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.9f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 500f), label = "bubble-squish"
    )
    Box(
        modifier = Modifier
            .size(diameter)
            .graphicsLayer { scaleX = squish; scaleY = squish }
            .clip(CircleShape)
            .background(IvyNearBlack.copy(alpha = 0.85f))
            .border(1.2.dp, GlassBorder, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    val t0 = System.currentTimeMillis()
                    val up = withTimeoutOrNull(HOLD_TO_RECORD_MS) { waitForUpOrCancellation() }
                    pressed = false
                    when {
                        up != null -> onTap()                                     // quick tap
                        System.currentTimeMillis() - t0 >= HOLD_TO_RECORD_MS -> { // held 2 s
                            onLongPress()
                            waitForUpOrCancellation()
                        }
                        // else: the finger dragged the bubble, nothing to do
                    }
                }
            }
            .testTag("bubble_idle"),
        contentAlignment = Alignment.Center
    ) {
        IvyLogoMark(size = diameter * 0.65f)
        if (ring.value > 0f) Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = IvyOrange, startAngle = -90f, sweepAngle = 360f * ring.value, useCenter = false,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                topLeft = Offset(1.5.dp.toPx(), 1.5.dp.toPx()), size = Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx())
            )
        }
    }
}

const val HOLD_TO_RECORD_MS = 500L

@Composable
fun BubbleMenuItem(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean,
    index: Int = 0, onClick: () -> Unit
) {
    val c = if (enabled) TextPrimary else TextMuted.copy(alpha = 0.5f)
    // the options come up out of the bubble one after the other (nearest to the bubble last)
    val enter = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(index * 40L); enter.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 18.dp.toPx() }
            .width(150.dp)
            .height(44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) IvyOrange else c, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, fontFamily = InterFamily, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c)
    }
}

/** One control, three stops: the sentence changes live as the knob moves. Tap a stop or drag the knob. */
@Composable
private fun ToneSlider(state: BubbleState) {
    val stops = state.toneStops
    val stopW = 64.dp // 74 was wider than the words need (Yash, 2026-11-12); labels 11.5 sp so "Grammar" keeps air in the knob
    val pad = 4.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val stopPx = with(density) { stopW.toPx() }
    var dragX by remember { mutableStateOf<Float?>(null) }
    val target = with(density) { (pad + stopW * state.toneLevel).toPx() }
    val knobX by androidx.compose.animation.core.animateFloatAsState(
        dragX ?: target,
        if (dragX != null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 320f),
        label = "tone-knob"
    )
    val glow by rememberInfiniteTransition(label = "tone-glow").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "tone-glow-a"
    )
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = Modifier
            .width(stopW * stops.size + pad * 2)
            .height(44.dp)
            .clip(shape)
            .background(IvyNearBlack.copy(alpha = 0.96f))
            .border(1.dp, GlassBorder, shape)
            .pointerInput(stops.size) {
                detectHorizontalDragGestures(
                    onDragStart = { dragX = (pad.toPx() + stopPx * state.toneLevel) },
                    onDragEnd = {
                        val x = dragX ?: 0f
                        val lvl = ((x - pad.toPx() + stopPx / 2) / stopPx).toInt().coerceIn(0, stops.size - 1)
                        dragX = null
                        if (lvl != state.toneLevel) state.onTone?.invoke(lvl)
                    },
                    onDragCancel = { dragX = null }
                ) { change, delta ->
                    change.consume()
                    dragX = ((dragX ?: 0f) + delta).coerceIn(pad.toPx(), pad.toPx() + stopPx * (stops.size - 1))
                }
            }
            .testTag("tone_slider")
    ) {
        stops.forEachIndexed { i, label ->
            Box(
                Modifier.offset(x = pad + stopW * i).width(stopW).fillMaxHeight()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (i != state.toneLevel) state.onTone?.invoke(i) },
                contentAlignment = Alignment.Center
            ) { Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = TextSecondary) }
        }
        // knob
        Box(
            Modifier
                .offset { IntOffset(knobX.roundToInt(), with(density) { 4.dp.roundToPx() }) }
                .width(stopW).height(36.dp)
                .graphicsLayer { if (state.toneBusy) alpha = glow }
                .clip(RoundedCornerShape(18.dp))
                .background(IvyOrange),
            contentAlignment = Alignment.Center
        ) {
            val nearest = ((knobX - with(density) { pad.toPx() } + stopPx / 2) / stopPx).toInt().coerceIn(0, stops.size - 1)
            Text(stops[nearest], fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, color = Color(0xFF1B0D04))
        }
    }
}

@Composable
private fun RecordingPill(
    durationSeconds: Int,
    waveformAmplitudes: List<Float>,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    val mins = durationSeconds / 60
    val secs = durationSeconds % 60
    val timeFormatted = String.format("%02d:%02d", mins, secs)
    val breathe by rememberInfiniteTransition(label = "rec-breathe").animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rec-breathe-a"
    )

    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(IvyNearBlack)
            .border(1.2.dp, IvyOrange.copy(alpha = breathe), RoundedCornerShape(23.dp))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Cancel: throws the recording away. Only the X, the same size as Done, so the pill stays short (Yash, 2026-11-12).
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onCancel
                )
                .testTag("bubble_cancel_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Close, contentDescription = "Cancel Dictation", tint = TextPrimary, modifier = Modifier.size(17.dp))
        }

        WaveformDisplay(
            amplitudes = waveformAmplitudes,
            modifier = Modifier.width(44.dp).height(18.dp)
        )

        Text(text = timeFormatted, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)

        // Done: stops and types the text
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(IvyOrange)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(color = Color.White),
                    onClick = onDone
                )
                .testTag("bubble_done_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Check, contentDescription = "Done Dictating", tint = TextPrimary, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun WaveformDisplay(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier
) {
    val smooth = amplitudes.map {
        androidx.compose.animation.core.animateFloatAsState(it, tween(110, easing = LinearEasing), label = "bar").value
    }
    Canvas(modifier = modifier) {
        val barCount = smooth.size
        val barWidth = 3.5.dp.toPx()
        val spacing = (size.width - (barCount * barWidth)) / (barCount - 1).coerceAtLeast(1)
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val amp = smooth.getOrElse(i) { 0.3f }.coerceIn(0.15f, 1.0f)
            val barHeight = (amp * maxHeight).coerceAtLeast(4.dp.toPx())
            val x = i * (barWidth + spacing)
            val y = (maxHeight - barHeight) / 2f

            drawRoundRect(
                color = IvyOrange,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

@Composable
private fun TranscribingPill() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerTranslate by transition.animateFloat(
        initialValue = -100f,
        targetValue = 200f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val shimmerBrush = Brush.horizontalGradient(
        colors = listOf(
            TextSecondary,
            IvyOrangeSoft,
            TextPrimary,
            TextSecondary
        ),
        startX = shimmerTranslate,
        endX = shimmerTranslate + 120f
    )

    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(IvyNearBlack)
            .border(1.dp, IvyOrange.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IvyLogoMark(size = 20.dp)
        Text(
            text = "Writing…",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = IvyOrangeSoft
        )
    }
}

@Composable
private fun PastedPill() {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(IvyNearBlack)
            .border(1.dp, IvyGreen.copy(alpha = 0.8f), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = "Pasted",
            tint = IvyGreen,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "Pasted",
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            color = TextPrimary
        )
    }
}

@Composable
private fun FallbackPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = IvyOrange),
            onClick = onClick
        )
    } else Modifier

    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(IvyNearBlack)
            .border(1.dp, GlassBorder, RoundedCornerShape(18.dp))
            .then(clickableModifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            color = TextPrimary
        )
    }
}
