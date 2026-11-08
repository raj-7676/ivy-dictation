package com.example.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/** The three things Ivy needs, read from Android itself (never cached), and the screens that grant them. */
object Permissions {
    fun mic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    /**
     * Granted when Ivy's service is on, OR when the user assigned it to an accessibility shortcut (Samsung: hold both
     * volume keys / Accessibility button / quick-settings tile). With a shortcut the service may be switched off between
     * uses, but the user has granted it and can toggle it at will, so it must not read as "fix".
     */
    fun accessibility(ctx: Context): Boolean {
        val me = ComponentName(ctx, IvyAccessibilityService::class.java)
        fun has(key: String): Boolean {
            val v = Settings.Secure.getString(ctx.contentResolver, key) ?: return false
            return v.split(':', ',', ';').any { ComponentName.unflattenFromString(it.trim()) == me }
        }
        return has(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ||
            has("accessibility_shortcut_target_service") ||
            has("accessibility_button_targets") ||
            has("accessibility_qs_targets")
    }

    fun overlayIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appInfoIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilityIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
