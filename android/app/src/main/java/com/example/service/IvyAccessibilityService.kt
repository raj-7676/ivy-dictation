package com.example.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.ClipboardManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.data.AudioFiles
import com.example.data.IvyStore
import com.example.data.Rules
import com.example.data.TextClean
import com.example.engine.Engine
import com.example.engine.ModelStore
import com.example.engine.LiveDictation
import com.example.ui.bubble.BubbleDockSide
import com.example.ui.bubble.BubbleEngine
import com.example.ui.bubble.BubbleSize
import com.example.ui.bubble.BubbleMode
import com.example.ui.bubble.BubbleOverlay
import com.example.ui.bubble.BubbleState
import com.example.ui.bubble.DismissTarget
import com.example.ui.theme.IvyTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The bubble in every app: shows the floating Ivy bubble while a text box has focus, records while it is on,
 * runs the on-device engine and types the text into that box (clipboard + "Copied" pill if the box refuses).
 */
class IvyAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleState
    private var view: View? = null // the bubble window: a FrameLayout host around the ComposeView
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, // real size is set in the state collector (see onServiceConnected)
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // never take focus: the text box keeps it, and the keyboard stays open
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.BOTTOM or Gravity.END }

    private var target: AccessibilityNodeInfo? = null
    private var appName = "App"
    private lateinit var prefs: android.content.SharedPreferences
    // Settings changes reach the bubble at once
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applySettings() }
    @Volatile private var dictation: LiveDictation? = null
    // start() runs on the main thread, finish() and cancel() on background threads and they wait there (cancel() for
    // seconds while the engine loads, with the bubble already idle). Under this lock they agree on which dictation is
    // the current one and whether the "listening" notification is still in use.
    private val micLock = Any()

    /** Hands out the running dictation once: a second cancel, or a cancel next to a finish, finds none. */
    private fun takeDictation(): LiveDictation? = synchronized(micLock) { dictation.also { dictation = null } }

    /** The "listening" notification goes, unless a dictation started meanwhile is using the microphone. */
    private fun micDone() = synchronized(micLock) { if (dictation == null) micForeground(false) }

    // The window hangs from the bottom of the screen: it grows UPWARD (menu / chip), so the bubble never moves.
    // Saved position stays "distance from the top to the bubble window's top" for old settings.
    private fun winPx() = WIN_H * resources.displayMetrics.density
    private fun applyTopY(yTop: Int) { params.y = (resources.displayMetrics.heightPixels - yTop - winPx()).toInt() }
    private fun topY() = (resources.displayMetrics.heightPixels - params.y - winPx()).toInt()
    // The bubble's centre from the docked edge of its window (6 dp side padding). Not the window's centre: the window
    // is still wide for a moment after the tone slider steps aside for a drag.
    private fun bubbleC() = (6f + bubble.bubbleSize.diameterDp.value / 2f) * resources.displayMetrics.density

    private val engine = object : BubbleEngine {
        override fun start(): Boolean {
            if (!Permissions.mic(this@IvyAccessibilityService)) return false
            if (secretBox()) return false // password / number / phone box: never listen (the bubble is hidden there anyway)
            if (!ModelStore.ready(this@IvyAccessibilityService)) {
                Log.w(TAG, "engine not ready: the speech model is not downloaded yet")
                return false
            }
            appName = appLabel(target?.packageName?.toString() ?: appRoot()?.packageName?.toString())
            synchronized(micLock) {
                micForeground(true)
                // the engine loads while the user talks and is freed again after a minute idle (Engine.release)
                dictation = LiveDictation({ Engine.acquire(this@IvyAccessibilityService) }, IvyStore.state.value.words.toTypedArray()).also { it.start() }
            }
            return true
        }

        override fun finish(): String? {
            val d = takeDictation() ?: return null
            lastUsedAt = android.os.SystemClock.uptimeMillis()
            return try {
                val r = d.finish()
                Log.i(TAG, "dictation ${r.audioSecs}s, ${r.afterReleaseMs} ms after release (read ${r.readMs}, write ${r.writeMs}, ${r.tokens} tok, windows ${r.windowsReused} reused/${r.windowsAtEnd} at end, drafts ${r.drafts} kept ${r.accepted}/${r.proposed}, slowest step ${r.maxWindowMs} ms), note '${r.note}'")
                // um / uh / er are removed and numbers become digits in every dictation; snippets are applied last, as on the desktop
                if (appName == "App") appName = appLabel(appRoot()?.packageName?.toString())
                val text = Rules.applySnippets(TextClean.afterModel(r.text), IvyStore.state.value.snippets)
                if (text.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    val audio = d.lastAudio?.let { AudioFiles.save(this@IvyAccessibilityService, it, now) }
                    IvyStore.addDictation(text, appName, (r.audioSecs * 1000).toLong(), audio, now)
                }
                text
            } catch (e: Throwable) {
                Log.e(TAG, "dictation failed", e)
                // The engine loads while the user talks, so a load that fails (low memory, a damaged model file) is
                // only known after they have spoken. Keep the recording in History: its Retry writes it later.
                runCatching {
                    d.lastAudio?.takeIf { it.size >= 8000 }?.let { pcm ->
                        val now = System.currentTimeMillis()
                        AudioFiles.save(this@IvyAccessibilityService, pcm, now)?.let { audio ->
                            // not counted: the words of this notice and the time spoken must not change the Home stats
                            IvyStore.addDictation(NOT_WRITTEN, appName, pcm.size / 16L, audio, now, counted = false)
                        }
                    }
                }
                null
            } finally {
                Engine.release()
                micDone()
            }
        }

        override fun cancel() {
            // The dictation is taken (cleared) BEFORE the wait: d.cancel() waits for its worker, which may be loading
            // the engine for seconds, while the bubble is already idle. A dictation the user starts in that time is
            // a new one: clearing afterwards wiped it, left its microphone on and its hold on the engine for ever.
            val d = takeDictation() ?: return
            d.cancel()
            lastUsedAt = android.os.SystemClock.uptimeMillis()
            Engine.release() // the hold d's worker took (Engine.acquire)
            micDone()
        }

        override fun level() = dictation?.level ?: 0f
    }

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        // the bubble/pill is drawn from this thread: keep it above the engine's compute threads (normal priority)
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        IvyStore.init(this)
        // Where the user last left it (default: right edge, above where the keyboard opens).
        prefs = getSharedPreferences("ivy", MODE_PRIVATE)
        val h0 = resources.displayMetrics.heightPixels
        applyTopY(prefs.getInt("bubble_y", (h0 * 0.42f).toInt()).coerceIn(h0 / 10, h0 * 8 / 10))
        bubble = BubbleState(this, scope).also { it.engine = engine }
        run { // start as exactly the bubble: explicit size from the first frame
            val dm = resources.displayMetrics
            params.width = ((bubble.bubbleSize.diameterDp.value + 12f) * dm.density).toInt()
            params.height = (WIN_H * dm.density).toInt()
        }
        bubble.onOpenHistory = {
            startActivity(Intent(this, com.example.MainActivity::class.java).putExtra("tab", "history")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        dock(if (prefs.getBoolean("bubble_left", false)) BubbleDockSide.LEFT else BubbleDockSide.RIGHT)
        // Let go: snap to the nearer side (never left floating mid-screen), like Wispr Flow's bubble.
        bubble.onDragEnd = onDragEnd@{
            // The drag handler below runs a moment after each move. A move still waiting when the finger lifts would
            // run after this and show the X again (and leave `dragging` on) for good: drop it here.
            // A quick flick lifts before the last moves were handled: judge the drop from where the finger let go.
            if (bubble.dragging || bubble.dragOffset != Offset.Zero) dragStep()
            bubble.dragOffset = Offset.Zero
            val wasHot = xHot
            xShown = false; xHot = false; bubble.dragging = false
            snapJob?.cancel(); snapJob = null
            xView?.let { xv -> scope.launch { delay(200); if (!xShown) xv.visibility = View.GONE } }
            if (wasHot && bubble.mode == BubbleMode.IDLE) { closeForThisBox(); return@onDragEnd }
            // the drag may go down to the X; a bubble left anywhere else goes back into its usual band
            val h = resources.displayMetrics.heightPixels
            applyTopY(topY().coerceIn(h / 10, h * 8 / 10))
            val w = resources.displayMetrics.widthPixels
            // which half the bubble itself is in (the window may still be wide from the tone slider)
            val bubbleX = if (bubble.dockSide == BubbleDockSide.LEFT) params.x + bubbleC() else w - params.x - bubbleC()
            dock(if (bubbleX < w / 2f) BubbleDockSide.LEFT else BubbleDockSide.RIGHT)
            prefs.edit().putBoolean("bubble_left", bubble.dockSide == BubbleDockSide.LEFT).putInt("bubble_y", topY()).apply()
        }
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IvyAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@IvyAccessibilityService)
            // While the pill is open the window keeps one fixed size, so the pill animates inside it instead of
            // the window resizing every frame (that resize was the clipped / jumping pill on the S26).
            setContent {
                IvyTheme {
                    // The content ALWAYS fills the window and hugs its docked corner. The window itself is resized
                    // separately (grows before the animation, shrinks after it), so the bubble can never be
                    // drawn at the wrong side of a window that is still the old size.
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = if (bubble.dockSide == BubbleDockSide.LEFT) Alignment.BottomStart else Alignment.BottomEnd
                    ) { BubbleOverlay(state = bubble, onTextEmitted = ::type, fillWidth = false) }
                }
            }
        }
        // The window moves under the finger while it is dragged, so the finger's place INSIDE the window lags (each
        // window move cancels part of the next finger move: a quick flick never reached the X, S26 2026-11-17).
        // The host notes where the finger is on the SCREEN, and the drag follows that.
        val host = object : android.widget.FrameLayout(this) {
            override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
                touchX = e.rawX; touchY = e.rawY
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) { downX = e.rawX; downY = e.rawY; downParamsX = params.x; downParamsY = params.y }
                return super.dispatchTouchEvent(e)
            }
        }.apply {
            // Compose looks for these on the window's root view, which is now the host
            setViewTreeLifecycleOwner(this@IvyAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@IvyAccessibilityService)
            addView(v); visibility = View.GONE
            // Docked at the screen edge, the bubble sits in Android's edge-swipe "Back" zone: dragging it sideways
            // went Back and closed the keyboard (S26 2026-11-17). Its own area is taken out of that gesture.
            if (Build.VERSION.SDK_INT >= 29) addOnLayoutChangeListener { lv, _, _, _, _, _, _, _, _ ->
                lv.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, lv.width, lv.height))
            }
        }
        // Android animates a window's position when its frame changes (the bubble used to slide in from the side every
        // time the window shrank). PRIVATE_FLAG_NO_MOVE_ANIMATION switches that off; hidden field, so best effort.
        runCatching {
            val f = WindowManager.LayoutParams::class.java.getField("privateFlags")
            f.setInt(params, f.getInt(params) or 0x00000040)
        }.onFailure { Log.w(TAG, "no-move-animation flag unavailable: $it") }
        params.windowAnimations = 0
        // never let the keyboard push or pan the bubble window (it used to drop to the bottom, then jump back, as the keyboard opened)
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        // The X target sits under the bubble window; it never takes touches (the drag stays with the bubble).
        xView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IvyAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@IvyAccessibilityService)
            setContent {
                IvyTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DismissTarget(xShown, xHot, X_SIZE) }
                }
            }
            visibility = View.GONE
        }
        val dm0 = resources.displayMetrics
        wm.addView(xView, WindowManager.LayoutParams(
            (X_BOX * dm0.density).toInt(), (X_BOX * dm0.density).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = ((X_BOTTOM - (X_BOX - X_SIZE) / 2) * dm0.density).toInt()
            windowAnimations = 0
        })
        wm.addView(host, params)
        view = host
        applySettings()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Dragging moves the window anywhere; letting go snaps it to a side (onDragEnd above).
        scope.launch {
            snapshotFlow { bubble.dragOffset }.collect { o -> if (o != Offset.Zero) dragStep() }
        }
        // Stay visible while busy even if the text box lost focus; hide again once idle. The wide window shrinks
        // back only after the pill's closing fade has finished.
        scope.launch {
            snapshotFlow { Triple(bubble.mode, bubble.chip, bubble.menuOpen) to (bubble.chip2 != null) to (bubble.toneVisible && !bubble.dragging) }.collectLatest { (st2, tone) ->
                val (st, c2) = st2
                val (m, c, menu) = st
                // Phone must not time out while listening
                val keep = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                val want = if (m == BubbleMode.RECORDING || m == BubbleMode.TRANSCRIBING) params.flags or keep else params.flags and keep.inv()
                if (want != params.flags) { params.flags = want; view?.let { wm.updateViewLayout(it, params) } }
                val dm = resources.displayMetrics
                val idleW = bubble.bubbleSize.diameterDp.value + 12f // bubble + its 6 dp side padding
                val (wDp, hDp) = when {
                    menu -> WIDE_W.toFloat() to MENU_H.toFloat()
                    tone -> WIDE_W.toFloat() to TONE_H.toFloat()
                    c2 -> WIDE_W.toFloat() to CHIP2_H.toFloat()
                    c != null -> WIDE_W.toFloat() to CHIP_H.toFloat()
                    m != BubbleMode.IDLE -> WIDE_W.toFloat() to WIN_H.toFloat()
                    else -> idleW to WIN_H.toFloat()
                }
                val tw = (wDp * dm.density).toInt(); val th = (hDp * dm.density).toInt()
                val grow = tw >= params.width && th >= params.height
                // let the closing animation finish before the window gets smaller; not while dragging: the slider goes
                // at once then, and a wide window would keep the bubble from reaching the X
                if (!grow && !bubble.dragging) delay(450)
                if (params.width != tw || params.height != th) {
                    params.width = tw; params.height = th
                    view?.let { wm.updateViewLayout(it, params) }
                }
                refresh()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // the user is typing (see warmWhenQuiet). Some boxes (Google search) report only text changes, others only
        // the moving cursor. A text change needs nothing else: return before the window scan, so typing stays cheap.
        val type = event?.eventType
        // the slider's text may have been deleted, changed, or left behind in another box
        if (bubble.toneVisible && (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED || type == AccessibilityEvent.TYPE_VIEW_FOCUSED)) checkTone()
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) { typedAt = android.os.SystemClock.uptimeMillis(); return }
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) typedAt = android.os.SystemClock.uptimeMillis()
        // while recording / writing the bubble stays up anyway; skip the window scans so the engine has the phone to itself
        if (view != null && bubble.mode == BubbleMode.IDLE) refresh()
    }

    private fun applySettings() {
        bubble.bubbleSize = runCatching { BubbleSize.valueOf(prefs.getString("bubble_size", "MEDIUM")!!) }.getOrDefault(BubbleSize.MEDIUM)
        bubble.bubbleOpacity = prefs.getFloat("bubble_opacity", 0.92f)
        val side = if (prefs.getBoolean("bubble_left", false)) BubbleDockSide.LEFT else BubbleDockSide.RIGHT
        if (side != bubble.dockSide) dock(side)
        refresh()
    }

    /** The app's own name ("Brave", "Samsung Notes"); if Android hides it, a readable guess from the package name. */
    private fun appLabel(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "App"
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        val skip = setOf("com", "org", "net", "android", "app", "apps", "mobile", "google", "samsung", "sec")
        return pkg.split('.').lastOrNull { it !in skip && it.isNotBlank() }?.replaceFirstChar { it.uppercase() } ?: "App"
    }

    private fun dock(side: BubbleDockSide) {
        bubble.dockSide = side
        params.gravity = Gravity.BOTTOM or (if (side == BubbleDockSide.LEFT) Gravity.START else Gravity.END)
        params.x = 0
        view?.let { wm.updateViewLayout(it, params) }
    }

    // Same rule as Wispr Flow: never in password, number or phone fields (PINs, card numbers, OTPs).
    private fun focusedBox(): AccessibilityNodeInfo? =
        try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
            ?.takeIf { n ->
                val cls = n.inputType and android.text.InputType.TYPE_MASK_CLASS
                n.isEditable && !n.isPassword && n.packageName != packageName &&
                    cls != android.text.InputType.TYPE_CLASS_NUMBER && cls != android.text.InputType.TYPE_CLASS_PHONE
            }

    // Like Wispr Flow: the bubble never shows in payment/banking apps (hidden, not blocked).
    private val restricted = setOf(
        "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
        "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android", "com.paypal.android.p2pmobile",
        "com.sbi.SBIFreedomPlus", "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.infrasofttech.centralbankapp",
        "com.upi.axispay", "com.fampay.in", "com.jar.app", "com.bharatpe.app", "com.amazon.mShop.android.shopping"
    )

    private fun sensitive(n: AccessibilityNodeInfo): Boolean {
        val cls = n.inputType and android.text.InputType.TYPE_MASK_CLASS
        return n.isPassword || cls == android.text.InputType.TYPE_CLASS_NUMBER || cls == android.text.InputType.TYPE_CLASS_PHONE
    }

    /** rootInActiveWindow is null in some apps (Samsung Notes): fall back to the app window that has focus. */
    private fun appRoot(): AccessibilityNodeInfo? = rootInActiveWindow ?: runCatching {
        val apps = windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        (apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.lastOrNull())?.root
    }.getOrNull()

    private fun keyboardUp() = runCatching {
        windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    /** Best guess at the field in front of the cursor; custom editors (Samsung Notes, web pages) need the fallbacks. */
    private fun findField(): AccessibilityNodeInfo? {
        focusedBox()?.let { return it }
        val root = appRoot() ?: return null
        for (kind in intArrayOf(AccessibilityNodeInfo.FOCUS_INPUT, AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)) {
            val n = try { root.findFocus(kind) } catch (_: Throwable) { null }
            if (n != null && n.isEditable && !sensitive(n)) return n
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen++ < 400) {
            val n = queue.removeFirst()
            if (n.isFocused && n.isEditable && !sensitive(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun refresh() {
        val box = focusedBox()
        if (box != null) target = box
        val busy = bubble.mode != BubbleMode.IDLE || bubble.chip != null || bubble.chip2 != null || bubble.menuOpen
        val pkg = appRoot()?.packageName?.toString()
        if (pkg != null && pkg in restricted) { setShown(busy); return }
        // Follows the keyboard, not the field: Samsung Notes / Firefox pages report no usable text box.
        // Hidden only for password, number and phone fields.
        val kb = keyboardUp()
        val raw = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
        val other = pkg != packageName
        val keyboardOk = !prefs.getBoolean("keyboard_only", true) || kb
        // secretBox: what the app told the keyboard. It also catches password boxes that show no usable node (web pages).
        val fieldOk = !secretBox() && (box != null || raw == null || !sensitive(raw))
        val here = keyboardOk && fieldOk && other && (box != null || kb)
        // Closed with the X: stays away in that box. Back when another box takes focus, or once the box is gone
        // (keyboard closed, app left) for longer than a focus flicker.
        if (closedHere) {
            if (here) {
                reopenJob?.cancel(); reopenJob = null
                if (closedBox != null && raw != null && raw != closedBox) { closedHere = false; closedBox = null }
            } else if (reopenJob == null) {
                reopenJob = scope.launch { delay(350); closedHere = false; closedBox = null; reopenJob = null }
            }
            if (closedHere) { setShown(busy); return }
        }
        // the text box is gone (Go / Search pressed, keyboard closed, app left): the controls for that text go too
        if (!here && bubble.toneVisible && bubble.mode == BubbleMode.IDLE && !bubble.toneBusy) { bubble.toneVisible = false; Log.i(TAG, "tone slider hide (box gone)") }
        setShown(here || busy || bubble.toneVisible)
    }

    // ---- Drag to the X: Ivy closes for this text box (like Wispr Flow) ----
    private var xView: ComposeView? = null
    private var xShown by mutableStateOf(false)
    private var xHot by mutableStateOf(false)
    private var closedHere = false
    private var closedBox: AccessibilityNodeInfo? = null // null when the app shows no box node (Samsung Notes, web pages)
    private var reopenJob: kotlinx.coroutines.Job? = null
    private var freeX = 0
    private var freeY = 0
    // where the finger is on the screen, and where it and the window were when it went down (see the host view)
    private var touchX = 0f
    private var touchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downParamsX = 0
    private var downParamsY = 0
    private var snapJob: kotlinx.coroutines.Job? = null

    /** One drag step: the bubble goes where the finger is on the screen; near the X it is pulled onto it. */
    private fun dragStep() {
        val h = resources.displayMetrics.heightPixels
        val w = resources.displayMetrics.widthPixels
        // x counts from the docked edge, so on the right side moving left makes it bigger
        val dx = (touchX - downX).let { if (bubble.dockSide == BubbleDockSide.LEFT) it else -it }
        val vw = view?.width ?: params.width
        // The finger's place (free*) is tracked apart from the window: while the X pulls the bubble in, the
        // window sits on the X, and pulling out puts it back under the finger.
        if (!bubble.dragging) { bubble.dragging = true; bubble.menuOpen = false }
        val wantX = (downParamsX + dx).toInt()
        freeX = wantX.coerceIn(0, w - vw)
        // down to the screen bottom while dragging (to reach the X); onDragEnd puts it back in its band
        freeY = (downParamsY - (touchY - downY)).toInt().coerceIn(0, (h - h / 10 - winPx()).toInt())
        bubble.dragOffset = Offset.Zero
        var hot = false
        val bubbleC = bubbleC()
        if (bubble.mode == BubbleMode.IDLE) {
            if (!xShown) { xView?.visibility = View.VISIBLE; xShown = true }
            // both centres measured from the bottom and the left of the screen (the windows hang from the bottom)
            val d = resources.displayMetrics.density
            // from the finger, not the window: a window still wide from the tone slider can't reach the middle yet
            val fx = wantX.coerceIn(0, (w - 2 * bubbleC).toInt())
            val bx = if (bubble.dockSide == BubbleDockSide.LEFT) fx + bubbleC else w - fx - bubbleC
            val by = freeY + bubble.bubbleSize.diameterDp.value * d / 2f
            // a wider ring to leave than to enter, so the bubble doesn't flick in and out at the edge
            val reach = (if (xHot) X_HOT * 1.4f else X_HOT.toFloat()) * d
            hot = Math.hypot((bx - w / 2f).toDouble(), (by - (X_BOTTOM + X_SIZE / 2f) * d).toDouble()) < reach
            if (hot && !xHot) xView?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        if (hot) {
            if (!xHot) { // pulled in: glide onto the X centre
                val d = resources.displayMetrics.density
                val tx = (w / 2f - bubbleC).toInt()
                val ty = ((X_BOTTOM + X_SIZE / 2f - bubble.bubbleSize.diameterDp.value / 2f) * d).toInt()
                snapJob?.cancel()
                snapJob = scope.launch {
                    val sx = params.x; val sy = params.y
                    for (i in 1..SNAP_FRAMES) {
                        val t = i.toFloat() / SNAP_FRAMES
                        val e = 1f - (1f - t) * (1f - t) // ease out
                        params.x = (sx + (tx - sx) * e).toInt(); params.y = (sy + (ty - sy) * e).toInt()
                        view?.let { wm.updateViewLayout(it, params) }
                        delay(16)
                    }
                }
            }
        } else {
            snapJob?.cancel(); snapJob = null
            params.x = freeX; params.y = freeY
            view?.let { wm.updateViewLayout(it, params) }
        }
        xHot = hot
    }

    private fun closeForThisBox() {
        closedHere = true
        closedBox = try { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Throwable) { null }
        reopenJob?.cancel(); reopenJob = null
        bubble.menuOpen = false; bubble.toneVisible = false; bubble.chip = null; bubble.chip2 = null
        // back to where it was before this drag (the saved place), ready for the next box
        val h = resources.displayMetrics.heightPixels
        applyTopY(prefs.getInt("bubble_y", (h * 0.42f).toInt()).coerceIn(h / 10, h * 8 / 10))
        dock(if (prefs.getBoolean("bubble_left", false)) BubbleDockSide.LEFT else BubbleDockSide.RIGHT)
        setShown(false) // frees the engine too, as when the box closes
        view?.visibility = View.GONE
        Log.i(TAG, "closed with the X for this box (node ${closedBox != null})")
    }

    private var hideJob: kotlinx.coroutines.Job? = null
    // The bubble is up (a text box is open): the speech engine loads now, so even a one-word dictation finds it
    // ready, and is freed a minute after the bubble goes (Engine.release).
    private var engineHeld = false
    // Warm the engine only after the box has been open and quiet (no typing) for WARM_QUIET_MS: a quick search (tap,
    // type, Enter) never triggers a load, so typing never competes with one. Holding the bubble loads at once anyway.
    private var warmJob: kotlinx.coroutines.Job? = null
    private var shownAt = 0L
    private var typedAt = 0L

    private suspend fun warmWhenQuiet() {
        while (true) {
            val wait = WARM_QUIET_MS - (android.os.SystemClock.uptimeMillis() - maxOf(shownAt, typedAt))
            if (wait <= 0) break
            delay(wait)
        }
        warmJob = null
        if (!engineHeld) {
            engineHeld = true
            lastUsedAt = android.os.SystemClock.uptimeMillis()
            Engine.acquireAsync(this)
            heldJob = scope.launch { letGoWhenUnused() }
        }
    }

    // A box left open and not touched (a chat with the keyboard up, the phone put down) must not hold ~1 GB for
    // ever: after HELD_IDLE_MS without a dictation the bubble lets go (Engine frees it a minute later). Any tap,
    // scroll or cursor move in the box warms the engine again 2 s later (setShown), so while the box is in use the
    // engine stays ready and a dictation never waits for a load. That is on purpose (decided 2026-11-10: speed
    // first while the box is in use; a let-go that held until the next dictation was tried and taken out).
    // A dictation in progress keeps its own hold, so nothing is freed under it.
    private var heldJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastUsedAt = 0L

    private suspend fun letGoWhenUnused() {
        while (engineHeld) {
            val wait = HELD_IDLE_MS - (android.os.SystemClock.uptimeMillis() - lastUsedAt)
            if (wait <= 0) { engineHeld = false; Engine.release(); break }
            delay(wait)
        }
        heldJob = null
    }

    /** Showing is instant; hiding waits 350 ms and is dropped if the bubble should be visible again (focus flicker). */
    private fun setShown(show: Boolean) {
        val v = view ?: return
        if (show) {
            hideJob?.cancel(); hideJob = null
            if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
            if (!engineHeld && warmJob == null) {
                shownAt = android.os.SystemClock.uptimeMillis()
                warmJob = scope.launch { warmWhenQuiet() }
            }
        } else if (v.visibility == View.VISIBLE && hideJob == null) {
            hideJob = scope.launch {
                delay(350); hideJob = null; view?.visibility = View.GONE
                // a hidden window may never get the finger's lift: end a drag here so the X can't stay on screen
                if (bubble.dragging) bubble.onDragEnd?.invoke()
                warmJob?.cancel(); warmJob = null
                heldJob?.cancel(); heldJob = null
                if (engineHeld) { engineHeld = false; Engine.release() }
            }
        }
    }

    private fun candidates(): List<AccessibilityNodeInfo> {
        val root = appRoot() ?: return emptyList()
        return collect(root)
    }

    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val cands = ArrayList<AccessibilityNodeInfo>()
        for (kind in intArrayOf(AccessibilityNodeInfo.FOCUS_INPUT, AccessibilityNodeInfo.FOCUS_ACCESSIBILITY))
            runCatching { root.findFocus(kind) }.getOrNull()?.let { cands.add(it) }
        val q = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (q.isNotEmpty() && seen++ < 500) {
            val n = q.removeFirst()
            val can = n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE || it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (can && (n.isFocused || n.isAccessibilityFocused || n.isEditable)) cands.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return cands
    }

    private fun pasteAnywhere(): Boolean {
        val root = appRoot() ?: run { Log.i(TAG, "paste: no root"); return false }
        val cands = collect(root)
        for (n in cands.sortedByDescending { it.isEditable }) {
            if (sensitive(n)) continue
            // Samsung Notes: the body box is editable but not focused, and ignores paste until it is
            if (!n.isFocused && n.isEditable) {
                n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Thread.sleep(120)
            }
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "paste ${n.className} editable=${n.isEditable} focused=${n.isFocused} -> $ok")
            if (ok) return true
        }
        Log.i(TAG, "paste: ${cands.size} candidates, none took it; pkg=${root.packageName}")
        return false
    }

    // Android 13+: typed through the keyboard's own channel (KeyboardLine). Older phones use the box actions below.
    private fun keyboardType(text: String) = Build.VERSION.SDK_INT >= 33 && KeyboardLine.type(this, text)
    private fun secretBox() = Build.VERSION.SDK_INT >= 33 && KeyboardLine.secret(this)

    // A box that opens its keyboard connection is checked at once (a password box hides the bubble before a tap can reach it).
    @androidx.annotation.RequiresApi(33)
    override fun onCreateInputMethod(): android.accessibilityservice.InputMethod =
        IvyInputMethod(this) {
            if (bubble.toneVisible) checkTone(150) // another box took the keyboard: the old text's slider goes
            if (view != null && bubble.mode == BubbleMode.IDLE) refresh()
        }
    private fun keyboardSwap(from: String, to: String) = Build.VERSION.SDK_INT >= 33 && KeyboardLine.swap(this, from, to)

    @Volatile private var clipUsed = false

    /** Puts the text where the cursor is in the focused box; clipboard + "Copied" pill if the box won't take it. */
    private fun type(text: String) {
        // the cursor moved into a password / number box while Ivy was listening: never typed there, kept on the
        // clipboard with the "Copied" pill instead (what Wispr Flow does for a sensitive field)
        if (secretBox()) { Log.i(TAG, "type: sensitive box, copied instead"); copy(text); bubble.triggerFallbackState(BubbleMode.FALLBACK_COPIED); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val old = runCatching { cm.primaryClip }.getOrNull()
        clipUsed = false
        scope.launch {
            // the paste attempts wait a little between steps: never on the main thread, or the pills would stutter
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { typeInto(text) }
            if (ok) {
                offerTone(text)
                if (!clipUsed) return@launch // typed like a keyboard: the clipboard was never touched
                // pasted: give the user's own clipboard back (the paste reads it asynchronously, so wait a moment)
                delay(600)
                runCatching { if (old != null) cm.setPrimaryClip(old) else if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() }
            } else {
                bubble.triggerFallbackState(BubbleMode.FALLBACK_COPIED)
            }
        }
    }

    /** True if the text went into a box; false if it is left on the clipboard ("Copied" pill). */
    private fun typeInto(text: String): Boolean {
        if (keyboardType(text)) return true
        val node = findField() ?: target?.takeIf { it.refresh() && it.isEditable }
        Log.i(TAG, "type: node=${node?.className} editable=${node?.isEditable} pkg=${appRoot()?.packageName}")
        if (node != null && insert(node, text)) return true
        // Custom editors: write straight into an editable box, no clipboard involved
        for (n in candidates().filter { it.isEditable && !sensitive(it) }) {
            // Samsung Notes: the body box ignores set-text until it has focus
            if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); Thread.sleep(100); n.refresh() }
            var done = insert(n, text, allowPaste = false)
            if (!done && !n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_CLICK); Thread.sleep(120); n.refresh(); done = insert(n, text, allowPaste = false) }
            if (done) { Log.i(TAG, "set-text into ${n.className}"); return true }
        }
        // No usable field node (Samsung Notes, web pages): put it on the clipboard, then try ACTION_PASTE on every
        // node that is focused or accepts paste/set-text. Only a node that really takes it counts.
        copy(text)
        if (pasteAnywhere()) return true
        copy(text) // stays on the clipboard for the "Copied" pill
        return false
    }

    // ---- Tone slider: Original | Grammar | Formal (nothing runs unless the user moves it) ----

    // No timeout (Yash, 2026-11-12): the slider belongs to its text and stays while that text is in the open box,
    // also while the user types on or moves the cursor. It goes when the box goes (refresh), when another box is
    // opened or the text is deleted or changed by hand (checkTone), and when the next recording starts.
    private var toneCheck: kotlinx.coroutines.Job? = null

    /** Asked a moment after things settle, never once per key press. */
    private fun checkTone(waitMs: Long = 400) {
        toneCheck?.cancel()
        toneCheck = scope.launch {
            delay(waitMs)
            // Ivy's own typing and swaps settle first (Samsung Notes shows a change late)
            while (bubble.toneBusy || System.currentTimeMillis() - maxOf(ownEditAt, lastToneAt) < 1200) delay(300)
            if (!bubble.toneVisible) return@launch
            val text = tones[bubble.toneLevel] ?: tones[0] ?: return@launch
            val here = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { textInBox(text) }
            if (!here && bubble.toneVisible && !bubble.toneBusy) {
                bubble.toneVisible = false; Log.i(TAG, "tone slider hide (its text is not in this box)")
            }
        }
    }

    /** True also when the box cannot be read at all: then only the box going away hides the slider. */
    private fun textInBox(text: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) KeyboardLine.has(this, text)?.let { return it }
        return findField()?.text?.toString()?.contains(text) ?: true
    }
    private val tones = arrayOfNulls<String>(3) // the three versions of the last dictation (made on first use)
    private var toneHasModel = false

    private var lastToneAt = 0L
    private var ownEditAt = 0L
    private var polishJob: kotlinx.coroutines.Job? = null

    private fun offerTone(original: String) {
        tones.fill(null); tones[0] = original
        pendingTone = null
        lastToneAt = System.currentTimeMillis()
        // 60 s window in which the tap menu offers "Polish last" (same idea as Wispr Flow's "last dictation, 60 seconds")
        bubble.polishReady = true
        bubble.onPolishLast = ::reopenTone
        polishJob?.cancel(); polishJob = scope.launch { delay(60_000); bubble.polishReady = false }
        toneHasModel = ModelStore.rewrite(this) != null
        if (toneHasModel) Engine.preloadRewriter(this) // ready by the time a finger reaches Grammar/Formal; frees itself after 60 s
        bubble.toneStops = if (toneHasModel) listOf("Original", "Grammar", "Formal") else listOf("Original", "Grammar")
        bubble.toneLevel = 0
        bubble.toneBusy = false
        bubble.onTone = ::moveTone
        bubble.toneVisible = true
        Log.i(TAG, "tone slider show (${bubble.toneStops.size} stops)")
    }

    /** "Polish last" from the tap menu: brings the slider back for the newest dictation, if its text is still there. */
    private fun reopenTone() {
        val cur = tones[bubble.toneLevel] ?: tones[0] ?: return
        scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                (listOfNotNull(findField()) + candidates().filter { it.isEditable }).any { it.text?.toString()?.contains(cur) == true }
            }
            if (!found) { bubble.polishReady = false; bubble.triggerFallbackState(BubbleMode.FALLBACK_NOTHING_TO_FIX); return@launch }
            bubble.toneVisible = true
        }
    }

    /** Grammar stop: the small model (word order, agreement, articles) first, then the safe rule pass; rules alone if the model is missing or wanders. */
    /** Words the user saved (Dictionary) and snippet texts: they must come out exactly as saved. */
    private fun protectedTerms(): List<String> =
        IvyStore.state.value.words + IvyStore.state.value.snippets.map { it.expansion }

    private fun grammarText(original: String): String {
        val mk = TextClean.mask(original, protectedTerms())
        val rules = TextClean.unmask(TextClean.grammar(mk.text), mk)
        val m = runCatching { Engine.rewrite(this, mk.text, grammar = true) }.getOrNull()?.trim()
        if (m.isNullOrBlank() || !TextClean.keptAll(m, mk)) return rules
        val ratio = m.length.toFloat() / mk.text.length.coerceAtLeast(1)
        if (ratio < 0.6f || ratio > 1.5f) return rules
        return TextClean.unmask(TextClean.grammar(m), mk)
    }

    /** Formal stop: the model's rewrite, then the desktop's Professional rules. When the model is missing, fails, or
     *  drops a saved word, the rules alone on the grammar-fixed text (it used to say "Already good" and snap back). */
    private fun formalText(original: String): String {
        val mk = TextClean.mask(original, protectedTerms())
        val m = runCatching { Engine.rewrite(this, mk.text) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (m != null && TextClean.keptAll(m, mk)) return TextClean.unmask(TextClean.tidy(TextClean.formal(m)), mk)
        Log.i(TAG, "formal: model ${if (m == null) "gave nothing" else "dropped a saved word"}, rules only")
        val base = tones[1] ?: TextClean.unmask(TextClean.grammar(mk.text), mk)
        val mk2 = TextClean.mask(base, protectedTerms())
        return TextClean.unmask(TextClean.tidy(TextClean.formal(mk2.text)), mk2)
    }

    private var pendingTone: Int? = null

    /** Moves the knob to [target] and swaps the sentence in the text box for that version. */
    private fun moveTone(target: Int) {
        // a tap while the last move is still writing is kept and done right after it (it used to be dropped)
        if (bubble.toneBusy) { pendingTone = target; return }
        if (target == bubble.toneLevel) return
        Log.i(TAG, "tone move ${bubble.toneLevel} -> $target")
        val from = bubble.toneLevel
        bubble.toneBusy = true
        bubble.toneLevel = target // the knob glides at once; the text follows
        scope.launch {
            try {
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    tones[target] ?: when (target) {
                        1 -> grammarText(tones[0]!!)
                        else -> formalText(tones[0]!!)
                    }.also { tones[target] = it }
                }
                Log.i(TAG, "tone text for $target: ${text.length} chars")
                if (text == tones[0] && from == 0) { // nothing to change (grammar, or formal of an already formal text)
                    bubble.toneLevel = 0; bubble.toneBusy = false
                    bubble.triggerFallbackState(BubbleMode.FALLBACK_NOTHING_TO_FIX); return@launch
                }
                val now = tones[from] ?: tones[0]!!
                ownEditAt = System.currentTimeMillis()
                val ok = if (text == now) true else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { swap(now, text) }
                ownEditAt = System.currentTimeMillis()
                Log.i(TAG, "tone swap ok=$ok")
                bubble.toneBusy = false
                if (!ok) {
                    bubble.toneLevel = from; bubble.toneVisible = false
                    copy(text); bubble.triggerFallbackState(BubbleMode.FALLBACK_COPIED); return@launch
                }
                if (text == tones[0] && target != 0) tones[target] = text
            } finally {
                bubble.toneBusy = false
                val next = pendingTone; pendingTone = null
                if (next != null && next != bubble.toneLevel && bubble.toneVisible) moveTone(next)
            }
        }
    }

    /** Replaces the last [from] in the focused box with [to], cursor after it. False if [from] isn't there now. */
    private fun swap(from: String, to: String): Boolean {
        if (keyboardSwap(from, to)) return true
        // Samsung Notes & co. report no focused field: look through every editable box for the one holding the text
        val nodes = listOfNotNull(findField(), target?.takeIf { it.refresh() && it.isEditable }) + candidates().filter { it.isEditable && !sensitive(it) }
        for (node in nodes) {
            val hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
            val cur = if (hint) "" else node.text?.toString() ?: continue
            val at = cur.lastIndexOf(from)
            if (at < 0) continue
            if (!node.isFocused) { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS); Thread.sleep(80); node.refresh() }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.substring(0, at) + to + cur.substring(at + from.length))
            }
            if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                // Samsung Notes refuses set-text but takes a paste: select the old text, paste the new one over it
                val sel = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at + from.length)
                })
                copy(to)
                val pasted = sel && node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.i(TAG, "swap: set-text refused by ${node.className}; select+paste -> $pasted")
                if (pasted) return true
                continue
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at + to.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at + to.length)
            })
            return true
        }
        Log.i(TAG, "swap: text not found in ${nodes.size} boxes")
        return false
    }

    private fun insert(node: AccessibilityNodeInfo, text: String, allowPaste: Boolean = true): Boolean {
        val hint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val cur = if (hint) "" else node.text?.toString() ?: ""
        var a = minOf(node.textSelectionStart, node.textSelectionEnd)
        var b = maxOf(node.textSelectionStart, node.textSelectionEnd)
        if (a < 0 || b > cur.length) { a = cur.length; b = cur.length }
        val before = cur.substring(0, a)
        val after = cur.substring(b)
        val piece = (if (before.isNotEmpty() && !before.last().isWhitespace()) " " else "") + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + piece + after)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            val end = a + piece.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, end)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            })
            return true
        }
        // Some boxes (web pages, custom editors) only take a paste.
        if (!allowPaste) return false
        copy(piece)
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun copy(text: String) {
        clipUsed = true
        val clip = ClipData.newPlainText("Ivy", text)
        // Android 13+: don't show the "Copied" preview of what was dictated
        clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }

    /** Android only lets a background app use the mic from a "microphone" foreground service (notification). */
    private fun micForeground(on: Boolean) {
        try {
            if (!on) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW))
            val n = Notification.Builder(this, CHANNEL)
                .setContentTitle("Ivy is listening")
                .setContentText("On this phone only")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(NOTIFY_ID, n)
        } catch (e: Throwable) {
            Log.w(TAG, "mic foreground service ${if (on) "start" else "stop"} failed", e)
        }
    }

    override fun onInterrupt() {
        // Samsung fires this on mic/audio changes while we record; we give no spoken feedback, so never cancel here.
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android is short of memory: give the models back now instead of after the idle minute (never mid-dictation)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            Thread { Engine.unloadIfIdle(); Engine.freeRewriter() }.start()  // both may wait for work in progress
        }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        engine.cancel() // a dictation in progress: stops it and gives back its hold on the engine
        warmJob?.cancel(); warmJob = null
        heldJob?.cancel(); heldJob = null
        if (engineHeld) { engineHeld = false; Engine.release() }
        view?.let { wm.removeView(it) }
        view = null
        xView?.let { wm.removeView(it) }
        xView = null
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "IvyBubble"
        const val CHANNEL = "ivy_listening"
        const val NOTIFY_ID = 7
        const val WIDE_W = 300 // dp: room for the widest pill (recording: cancel, wave, timer, done)
        const val WIN_H = 64   // dp
        const val MENU_H = 220 // dp: bubble + the History / Record / Cancel menu
        const val TONE_H = 120 // dp: bubble + the tone slider
        const val WARM_QUIET_MS = 2000L
        const val HELD_IDLE_MS = 120_000L
        // History text of a dictation whose recording is kept but could not be written (see engine.finish)
        const val NOT_WRITTEN = "Couldn't write this one. Tap Retry to try again."
        const val CHIP2_H = 162 // dp: bubble + both chips ("Fix grammar" above "Make it professional?")
        const val CHIP_H = 112 // dp: bubble + the "Make it professional?" / "Undo" chip
        const val X_SIZE = 56   // dp: the X circle
        const val X_BOX = 90    // dp: its window (room to grow when the bubble is over it)
        const val X_BOTTOM = 40 // dp: from the bottom of the screen to the X circle
        const val X_HOT = 64    // dp: bubble centre this close to the X centre = pulled in; drop closes Ivy
        const val SNAP_FRAMES = 8 // ~130 ms glide onto the X
    }
}
