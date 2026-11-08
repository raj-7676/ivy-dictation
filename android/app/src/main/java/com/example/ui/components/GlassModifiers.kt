package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.SyneFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

fun Modifier.glassCard(
    shape: Shape = RoundedCornerShape(22.dp),
    fillColor: Color = GlassFill,
    borderColor: Color = GlassBorder,
    borderWidth: Dp = 1.dp
): Modifier = this
    .clip(shape)
    .background(fillColor)
    .border(borderWidth, borderColor, shape)

@Composable
fun IvyGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickableModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = ripple(color = IvyOrange.copy(alpha = 0.2f)),
            onClick = onClick
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .glassCard(shape = shape)
            .then(clickableModifier)
    ) {
        content()
    }
}

@Composable
fun IvyLogoMark(
    modifier: Modifier = Modifier,
    size: Dp = 32.dp
) {
    // The real Ivy mark is a 6:5 faceted V (orange + white, never recoloured), not a circle: no clip.
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_ivy_logo),
            contentDescription = "Ivy Logo Mark",
            tint = Color.Unspecified,
            modifier = Modifier.size(width = size, height = size * 162f / 192f)
        )
    }
}

@Composable
fun IvyWordmark(
    modifier: Modifier = Modifier,
    fontSize: Int = 22,
    showLogo: Boolean = true,
    logoSize: Dp = 26.dp,
    onLongClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showLogo) {
            IvyLogoMark(size = logoSize)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = "Ivy",
            fontFamily = SyneFontFamily,
            fontWeight = FontWeight.ExtraBold, // 800
            fontSize = fontSize.sp,
            color = TextPrimary,
            letterSpacing = (-0.5).sp
        )
    }
}

@Composable
fun IvyBrandFooter(
    modifier: Modifier = Modifier
) {
    Text(
        text = "By Glow.co",
        fontFamily = com.example.ui.theme.InterFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextMuted,
        modifier = modifier
    )
}

/**
 * Android real tactile vibration feedback helper.
 */
fun performTactileFeedback(context: Context, durationMs: Long = 25) {
    // Settings > Haptic feedback
    if (!context.getSharedPreferences("ivy", Context.MODE_PRIVATE).getBoolean("haptics", true)) return
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        vibrator?.let {
            if (it.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(durationMs)
                }
            }
        }
    } catch (_: Exception) {
        // Safe catch for simulator or restricted environments
    }
}
