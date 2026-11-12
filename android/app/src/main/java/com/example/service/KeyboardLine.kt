package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.text.InputType
import android.util.Log
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

/**
 * Android 13+: the channel a keyboard types through, open to accessibility services (flagInputMethodEditor).
 * Works in every box a keyboard works in (Swiggy, Zomato, Blinkit and other boxes that refuse set-text and paste),
 * at the cursor, with no clipboard. Kept in its own file so phones older than Android 13 never load these types.
 */
@RequiresApi(33)
internal object KeyboardLine {
    private const val TAG = "IvyBubble"

    // Their address bar may be marked "visible password" (private tabs, so the keyboard learns nothing). Web page
    // password boxes are "web password", so in these apps a visible-password box can only be the browser's own bar.
    private val browsers = setOf(
        "com.android.chrome", "com.brave.browser", "org.mozilla.firefox", "com.microsoft.emmx",
        "com.sec.android.app.sbrowser", "com.opera.browser", "com.duckduckgo.mobile.android", "com.vivaldi.browser"
    )

    /**
     * The open box is a password, PIN, number or phone box, as the app told the keyboard (the flag that makes the
     * keyboard show dots or a number pad). Same rule as Wispr Flow's help pages (2026-11): password boxes including
     * visible and web passwords and boxes an app marks as password, numeric-only and phone boxes; browser address
     * bars are the exception. Ivy does not listen or type there. This also sees boxes with no usable accessibility
     * node (web pages, where the node check found nothing).
     */
    fun secret(s: AccessibilityService): Boolean {
        val im = s.inputMethod ?: return false
        if (!im.currentInputStarted) return false
        val info = im.currentInputEditorInfo ?: return false
        val v = info.inputType and InputType.TYPE_MASK_VARIATION
        return when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                (v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD && info.packageName !in browsers)
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE -> true
            else -> false
        }
    }

    private fun line(s: AccessibilityService): InputMethod.AccessibilityInputConnection? {
        val im = s.inputMethod
        val ic = if (im != null && im.currentInputStarted && !secret(s)) im.currentInputConnection else null
        if (ic == null) Log.i(TAG, "keyboard line: none (service=${im != null} started=${im?.currentInputStarted})")
        return ic
    }

    /**
     * Types [text] at the cursor like a keyboard. False only if nothing was sent. Once the text is sent this is
     * true: a keyboard is not told more either, and a second attempt by another way would write the text twice
     * (Samsung Notes: its box did not show the change at once, so the old paste ran on top).
     */
    fun type(s: AccessibilityService, text: String): Boolean {
        val ic = line(s) ?: return false
        return runCatching {
            val prev = runCatching { ic.getSurroundingText(1, 0, 0) }.getOrNull()
                ?.let { it.text.take(it.selectionStart.coerceIn(0, it.text.length)) }
            val piece = (if (!prev.isNullOrEmpty() && !prev.last().isWhitespace()) " " else "") + text
            ic.commitText(piece, 1, null)
            // Samsung Notes ends a text sent this way with a line break of its own (seen 2026-11-10: every dictation
            // on its own line, and the tone slider no longer found the cursor after the text). Take out a line
            // break that was not sent: only when it sits right after our own words.
            val tail = piece.takeLast(40)
            val now = if (tail.endsWith('\n')) null else runCatching { ic.getSurroundingText(tail.length + 1, 0, 0) }.getOrNull()
            val added = now != null && now.selectionStart == now.selectionEnd &&
                now.text.take(now.selectionStart.coerceIn(0, now.text.length)).endsWith(tail + "\n")
            if (added) ic.deleteSurroundingText(1, 0)
            Log.i(TAG, "keyboard line: typed${if (added) " (line break removed)" else ""}, pkg=${s.inputMethod?.currentInputEditorInfo?.packageName}")
            true
        }.getOrElse { Log.w(TAG, "keyboard line failed: $it"); false }
    }

    private const val LOOK = 4000 // characters read on each side of the cursor

    /** Where [text] starts in what was read around the cursor, or -1. The copy that ends at or before the cursor wins. */
    private fun locate(st: android.view.inputmethod.SurroundingText, text: String): Int {
        val s = st.text.toString()
        val cur = st.selectionStart.coerceIn(0, s.length)
        val before = if (cur >= text.length) s.lastIndexOf(text, cur - text.length) else -1
        return if (before >= 0) before else s.indexOf(text)
    }

    /**
     * Is [text] still in the open box? The tone slider belongs to its text: it stays only while this is true.
     * Null when the box cannot be read (no keyboard connection, or an app that does not answer).
     */
    fun has(s: AccessibilityService, text: String): Boolean? {
        val im = s.inputMethod ?: return null
        if (!im.currentInputStarted) return null
        if (secret(s)) return false
        val ic = im.currentInputConnection ?: return null
        val n = maxOf(LOOK, text.length * 2)
        return runCatching { ic.getSurroundingText(n, n, 0)?.let { locate(it, text) >= 0 } }.getOrNull()
    }

    /**
     * Tone slider: swaps [from] for [to] wherever [from] sits in the open box. The cursor is usually right after it;
     * if the user typed on or moved the cursor, the text is replaced where it is and the cursor is put back.
     */
    fun swap(s: AccessibilityService, from: String, to: String): Boolean {
        val ic = line(s) ?: return false
        return runCatching {
            val n = maxOf(LOOK, from.length * 2)
            val st = ic.getSurroundingText(n, n, 0)
            val at = if (st == null) -1 else locate(st, from)
            if (st == null || at < 0) {
                Log.i(TAG, "keyboard line swap: text not in the box (readable=${st != null})")
                return false
            }
            val a = st.selectionStart
            val b = st.selectionEnd
            if (a == b && a == at + from.length) {
                ic.deleteSurroundingText(from.length, 0)
                ic.commitText(to, 1, null)
            } else {
                val base = st.offset
                ic.setSelection(base + at, base + at + from.length)
                ic.commitText(to, 1, null) // replaces the selection
                fun moved(p: Int) = when {
                    p <= at -> p
                    p >= at + from.length -> p + to.length - from.length
                    else -> at + to.length
                }
                ic.setSelection(base + moved(a), base + moved(b))
                Log.i(TAG, "keyboard line swap: replaced away from the cursor")
            }
            true
        }.getOrElse { Log.w(TAG, "keyboard line swap failed: $it"); false }
    }
}

/** Tells the bubble the moment a box opens its keyboard connection, so a password box hides it at once. */
@RequiresApi(33)
internal class IvyInputMethod(s: AccessibilityService, private val onBox: () -> Unit) : InputMethod(s) {
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        onBox()
    }
}
