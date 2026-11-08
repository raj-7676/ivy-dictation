package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Drifting glass motes behind the app, the same ambient layer as the desktop app (src/components/GlassParticles.tsx):
 * circles, diamonds and shards in orange + white rise slowly and twinkle. Drawn behind all content; it stops
 * while the app is not on screen.
 */
private class Mote(
    var x: Float, var y: Float,
    val vx: Float, val vy: Float,
    val size: Float, val baseAlpha: Float,
    val twinkle: Float, var phase: Float,
    val type: Int, var rotation: Float, val rotSpeed: Float,
)

@Composable
fun GlassParticles(modifier: Modifier = Modifier, count: Int = 26, accent: Color = Color(0xFFFF6B00)) {
    val density = LocalDensity.current.density
    val motes = remember { mutableListOf<Mote>() }
    val frame = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val dt = if (last == 0L) 1f else ((now - last) / 16_000_000f).coerceAtMost(2f)
                    last = now
                    for (m in motes) {
                        m.x += m.vx * dt
                        m.y += m.vy * dt
                        m.rotation += m.rotSpeed * dt
                        m.phase += m.twinkle * dt
                    }
                    frame.longValue = now
                }
            }
        }
    }

    Canvas(modifier) {
        frame.longValue // redraw every frame
        val w = size.width
        val h = size.height
        if (motes.isEmpty() && w > 0f) {
            val r = Random(7)
            repeat(count) {
                motes += Mote(
                    x = r.nextFloat() * w, y = r.nextFloat() * h,
                    vx = (r.nextFloat() - 0.5f) * 0.35f * density, vy = (-0.2f - r.nextFloat() * 0.45f) * density,
                    size = (1.2f + r.nextFloat() * 2.8f) * density, baseAlpha = 0.18f + r.nextFloat() * 0.32f,
                    twinkle = 0.02f + r.nextFloat() * 0.035f, phase = r.nextFloat() * 2f * PI.toFloat(),
                    type = intArrayOf(0, 1, 2, 0)[r.nextInt(4)], rotation = r.nextFloat() * 360f,
                    rotSpeed = (r.nextFloat() - 0.5f) * 1.2f,
                )
            }
        }
        val pad = 10f * density
        for (m in motes) {
            if (m.y < -pad) { m.y = h + pad; m.x = Random.nextFloat() * w }
            if (m.x < -pad) m.x = w + pad else if (m.x > w + pad) m.x = -pad
            val a = (m.baseAlpha + sin(m.phase) * 0.18f).coerceIn(0.05f, 0.85f)
            val s = m.size
            translate(m.x, m.y) {
                rotate(m.rotation, pivot = Offset.Zero) {
                    when (m.type) {
                        0 -> drawCircle(
                            Brush.radialGradient(
                                0f to Color.White.copy(alpha = (a * 1.2f).coerceAtMost(1f)),
                                0.35f to accent.copy(alpha = a),
                                1f to accent.copy(alpha = 0f),
                                center = Offset.Zero, radius = s * 2.2f,
                            ),
                            radius = s * 2.2f, center = Offset.Zero,
                        )
                        1 -> {
                            val p = Path().apply {
                                moveTo(0f, -s * 1.4f); lineTo(s * 0.9f, 0f); lineTo(0f, s * 1.4f); lineTo(-s * 0.9f, 0f); close()
                            }
                            drawPath(p, Color.White.copy(alpha = (a * 1.1f).coerceAtMost(1f)))
                            drawPath(p, accent.copy(alpha = a * 0.9f), style = Stroke(width = 0.6f * density))
                        }
                        else -> {
                            val p = Path().apply {
                                moveTo(0f, -s * 1.8f); lineTo(s * 0.4f, 0f); lineTo(0f, s * 1.8f); lineTo(-s * 0.4f, 0f); close()
                            }
                            drawPath(p, accent.copy(alpha = a * 0.8f))
                            drawCircle(Color.White.copy(alpha = (a * 1.4f).coerceAtMost(1f)), radius = s * 0.45f, center = Offset.Zero)
                        }
                    }
                }
            }
        }
    }
}
