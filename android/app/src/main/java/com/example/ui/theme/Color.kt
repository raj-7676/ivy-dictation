package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Ivy Strict Brand Colors
val IvyNearBlack = Color(0xFF0A080E)
val IvySurfaceDark = Color(0xFF120E18)
val IvyOrange = Color(0xFFFF6B00)
val IvyOrangeSoft = Color(0xFFFF8533)
val IvyOrangeGlow = Color(0x33FF6B00)
val IvyGreen = Color(0xFF10B981)

val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0x99FFFFFF) // 60% white
val TextMuted = Color(0x66FFFFFF) // 40% white

// Solid surfaces, no glass (Yash, 2026-11-03: the see-through glass looked goofy and choppy). The names stay so every
// screen picks up the same look: one surface colour for cards, one hairline, one raised tone for chips/selection.
// They follow Settings > Appearance > Glass opacity (Appearance.cardAlpha): solid at 100%, see-through below.
val GlassFill: Color get() = Color(0xFF15121B).copy(alpha = Appearance.cardAlpha)       // card surface
val GlassBorder: Color get() = Color(0xFF231E2A).copy(alpha = Appearance.cardAlpha)     // hairline around cards
val GlassFillActive: Color get() = Color(0xFF201B27).copy(alpha = Appearance.cardAlpha) // chips, selected rows
