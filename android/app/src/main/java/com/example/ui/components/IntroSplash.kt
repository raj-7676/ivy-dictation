package com.example.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.IvyNearBlack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Set when the app-start logo intro has finished; screens that animate on first launch wait for it. */
object AppIntro {
    var finished by androidx.compose.runtime.mutableStateOf(false)
}

private val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/**
 * App-start intro, deliberately minimal (like Gemini / Claude / ChatGPT): only the Ivy mark, no letters.
 * The mark fades and settles in from slightly smaller while a soft orange glow blooms behind it, holds a beat,
 * then the whole screen fades away (the caller's fadeOut) to reveal the app. About 1.3 s in total.
 */
@Composable
fun IntroSplash(onFinished: () -> Unit) {
    val appear = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(650, easing = EaseOut)) }
        launch {
            delay(150)
            glow.animateTo(1f, tween(700, easing = EaseOut))
        }
        delay(1000)
        AppIntro.finished = true // set before leaving: this coroutine is cancelled once the intro is removed
        onFinished()
    }
    Box(
        modifier = Modifier.fillMaxSize().background(IvyNearBlack),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(260.dp).alpha(glow.value * 0.9f)) {
            drawCircle(
                Brush.radialGradient(
                    0f to Color(0xFFFF6B00).copy(alpha = 0.35f),
                    0.45f to Color(0xFFFF6B00).copy(alpha = 0.12f),
                    1f to Color.Transparent,
                ),
                radius = size.minDimension / 2f * (0.7f + 0.3f * glow.value),
            )
        }
        IvyLogoMark(
            size = 88.dp,
            modifier = Modifier
                .alpha(appear.value)
                .scale(0.86f + 0.14f * appear.value)
        )
    }
}
