package com.example.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Settings > Appearance > Glass opacity, like desktop Ivy: how solid the app is over the phone's wallpaper
 * (1 = solid dark, lower = see-through). Background and cards fade together, cards a little less so text stays
 * readable. (A background-blur switch was tried and dropped, Yash 2026-11-03: needs a system setting most phones
 * have off.) Kept in SharedPreferences; Compose recomposes when it changes.
 */
object Appearance {
    private const val PREFS = "ivy_appearance"
    var glassOpacity by mutableFloatStateOf(1f)

    fun load(context: Context) {
        glassOpacity = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("glass_opacity", 1f)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("glass_opacity", glassOpacity).apply()
    }

    /** Card/surface alpha: follows the slider but never drops below 55% so text stays readable. */
    val cardAlpha: Float get() = 0.55f + 0.45f * glassOpacity
}
