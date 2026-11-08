package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IvyDarkColorScheme = darkColorScheme(
    primary = IvyOrange,
    onPrimary = TextPrimary,
    primaryContainer = IvySurfaceDark,
    onPrimaryContainer = IvyOrangeSoft,
    secondary = IvyOrangeSoft,
    onSecondary = TextPrimary,
    background = IvyNearBlack,
    onBackground = TextPrimary,
    surface = IvySurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = GlassFill,
    onSurfaceVariant = TextSecondary,
    outline = GlassBorder,
    outlineVariant = GlassBorder
)

@Composable
fun IvyTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = IvyDarkColorScheme,
        typography = IvyTypography,
        content = content
    )
}
