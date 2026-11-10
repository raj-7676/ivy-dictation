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
    private var view: ComposeView? = null
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

    private val engine = object : BubbleEngine {
        override fun start(): Boolean {
            if (!Permissions.mic(this@IvyAccessibilityService)) return false
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
                // um / uh / er are removed from every dictation; snippets are applied last, as on the desktop
                if (appName == "App") appName = appLabel(appRoot()?.packageName?.toString())
                val text = Rules.applySnippets(TextClean.fillers(r.text).trim(), IvyStore.state.value.snippets)
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
        bubble.onDragEnd = {
            val w = resources.displayMetrics.widthPixels
            val vw = view?.width ?: 0
            val fromLeft = if (bubble.dockSide == BubbleDockSide.LEFT) params.x else w - params.x - vw
            dock(if (fromLeft + vw / 2 < w / 2) BubbleDockSide.LEFT else BubbleDockSide.RIGHT)
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
            visibility = View.GONE
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
        wm.addView(v, params)
        view = v
        applySettings()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        // Dragging moves the window anywhere; letting go snaps it to a side (onDragEnd above).
        scope.launch {
            snapshotFlow { bubble.dragOffset }.collect { o ->
                if (o == Offset.Zero) return@collect
                val h = resources.displayMetrics.heightPixels
                val w = resources.displayMetrics.widthPixels
                // x counts from the docked edge, so on the right side moving left makes it bigger
                val dx = if (bubble.dockSide == BubbleDockSide.LEFT) o.x.toInt() else -o.x.toInt()
                params.x = (params.x + dx).coerceIn(0, w - (view?.width ?: 0))
                applyTopY((topY() + o.y.toInt()).coerceIn(h / 10, h * 8 / 10))
                bubble.dragOffset = Offset.Zero
                view?.let { wm.updateViewLayout(it, params) }
            }
        }
        // Stay visible while busy even if the text box lost focus; hide again once idle. The wide window shrinks
        // back only after the pill's closing fade has finished.
        scope.launch {
            snapshotFlow { Triple(bubble.mode, bubble.chip, bubble.menuOpen) to (bubble.chip2 != null) to bubble.toneVisible }.collectLatest { (st2, tone) ->
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
                if (!grow) delay(450) // let the closing animation finish before the window gets smaller
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
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) { typedAt = android.os.SystemClock.uptimeMillis(); return }
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) typedAt = android.os.SystemClock.uptimeMillis()
        if (event != null && bubble.toneVisible && !bubble.toneBusy && event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            System.currentTimeMillis() - ownEditAt > 1200 && System.currentTimeMillis() - lastToneAt > 1200) {
            bubble.toneVisible = false; Log.i(TAG, "tone slider hide (user touched the text)")
        }
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
        val fieldOk = box != null || (raw == null || !sensitive(raw))
        val here = keyboardOk && fieldOk && other && (box != null || kb)
        // the text box is gone (Go / Search pressed, keyboard closed, app left): the controls for that text go too
        if (!here && bubble.toneVisible && bubble.mode == BubbleMode.IDLE && !bubble.toneBusy) { bubble.toneVisible = false; Log.i(TAG, "tone slider hide (box gone)") }
        setShown(here || busy || bubble.toneVisible)
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

    /** Puts the text where the cursor is in the focused box; clipboard + "Copied" pill if the box won't take it. */
    private fun type(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val old = runCatching { cm.primaryClip }.getOrNull()
        scope.launch {
            // the paste attempts wait a little between steps: never on the main thread, or the pills would stutter
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { typeInto(text) }
            if (ok) {
                offerTone(text)
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

    private var toneJob: kotlinx.coroutines.Job? = null
    private val tones = arrayOfNulls<String>(3) // the three versions of the last dictation (made on first use)
    private var toneHasModel = false

    private fun keepToneAlive() {
        toneJob?.cancel()
        toneJob = scope.launch { delay(6000); bubble.toneVisible = false; Log.i(TAG, "tone slider hide (6 s)") }
    }

    private var lastToneAt = 0L
    private var ownEditAt = 0L
    private var polishJob: kotlinx.coroutines.Job? = null

    private fun offerTone(original: String) {
        tones.fill(null); tones[0] = original
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
        keepToneAlive()
    }

    /** "Polish last" from the tap menu: brings the slider back for the newest dictation, if its text is still there. */
    private fun reopenTone() {
        val cur = tones[bubble.toneLevel] ?: tones[0] ?: return
        scope.launch {
            val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                (listOfNotNull(findField()) + candidates().filter { it.isEditable }).any { it.text?.toString()?.contains(cur) == true }
            }
            if (!found) { bubble.polishReady = false; bubble.triggerFallbackState(BubbleMode.FALLBACK_NOTHING_TO_FIX); return@launch }
            bubble.toneVisible = true; keepToneAlive()
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

    private fun formalText(original: String): String? {
        val mk = TextClean.mask(original, protectedTerms())
        val m = runCatching { Engine.rewrite(this, mk.text) }.getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (!TextClean.keptAll(m, mk)) return null // the model dropped or changed a saved word: do not trust it
        return TextClean.unmask(m, mk)
    }

    /** Moves the knob to [target] and swaps the sentence in the text box for that version. */
    private fun moveTone(target: Int) {
        if (bubble.toneBusy || target == bubble.toneLevel) return
        Log.i(TAG, "tone move ${bubble.toneLevel} -> $target")
        val from = bubble.toneLevel
        bubble.toneBusy = true
        bubble.toneLevel = target // the knob glides at once; the text follows
        toneJob?.cancel()
        scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                tones[target] ?: when (target) {
                    1 -> grammarText(tones[0]!!)
                    else -> formalText(tones[0]!!)
                }.also { tones[target] = it }
            }
            if (text == null) { // the model gave nothing: go back
                bubble.toneLevel = from; bubble.toneBusy = false; keepToneAlive()
                bubble.triggerFallbackState(BubbleMode.FALLBACK_NOTHING_TO_FIX); return@launch
            }
            Log.i(TAG, "tone text for $target: ${if (text == null) "none" else "${text.length} chars"}")
            if (target == 1 && text == tones[0] && from == 0) { // grammar found nothing to change
                bubble.toneLevel = 0; bubble.toneBusy = false; keepToneAlive()
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
            keepToneAlive()
        }
    }

    /** Replaces the last [from] in the focused box with [to], cursor after it. False if [from] isn't there now. */
    private fun swap(from: String, to: String): Boolean {
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
    }
}
