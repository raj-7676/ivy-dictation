package com.example.engine

import android.content.Context

/**
 * The one engine the app and the bubble share (about 1.4 GB of memory while loaded). Loaded when a text box opens
 * (the bubble shows) or a dictation starts, and freed [IDLE_S] after the last user lets go, or at once when Android
 * runs low on memory: kept loaded forever it held 2.5 GB on an idle S26 and slowed the phone (2026-11-09).
 */
object Engine {
    @Volatile var handle = 0L
        private set
    @Volatile var error = ""
        private set
    // Two locks: loading/freeing takes seconds (loadLock); the user count and idle timer (stateLock) are touched from
    // the main thread when a text box opens or closes and must never wait for a load.
    private val loadLock = Object()
    private val stateLock = Object()
    private const val IDLE_S = 60L
    private var users = 0
    private val timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile private var loaderTid = 0
    @Volatile private var cpuLogged = false

    /** CPU code, once per process: the version measured fastest on this chip (kept in filesDir until an update). */
    fun initBackends(app: Context) {
        IvyNative.initBackends(app.applicationInfo.nativeLibraryDir, app.filesDir.absolutePath)
        if (!cpuLogged) { cpuLogged = true; android.util.Log.i("IvyEngine", "cpu code: " + IvyNative.cpuInfo()) }
    }

    /**
     * Blocks until the engine is ready (needs the downloaded model). Returns 0 on failure, see [error].
     * [background]: a load nobody waits for yet (text box opened) runs below normal priority so typing never stutters.
     */
    fun load(ctx: Context, background: Boolean = false): Long = synchronized(loadLock) {
        if (handle != 0L) return handle
        val app = ctx.applicationContext
        try {
            if (!ModelStore.ready(app)) { error = "The speech model is not downloaded yet"; return 0L }
            // Normal priority, not URGENT_AUDIO: at -19 the engine starved the bubble's drawing (CMF 2026-11-09: pill
            // frames up to 250 ms). A background load runs at +5 (below the keyboard and the app being typed in, but
            // not in Android's background group, which would pin it to the slow cores); the native side creates the
            // compute threads at normal priority either way.
            runCatching {
                android.os.Process.setThreadPriority(if (background) BACKGROUND_LOAD_PRIORITY else android.os.Process.THREAD_PRIORITY_DEFAULT)
            }
            loaderTid = android.os.Process.myTid()
            // after the priority: the first start after an install times the CPU code versions (~1-2 s, once)
            initBackends(app)
            handle = IvyNative.load(ModelStore.model(app), ModelStore.mmproj(app), 0)
            if (handle == 0L) error = IvyNative.lastError()
        } catch (e: Throwable) {
            error = e.toString()
        } finally {
            loaderTid = 0
            if (background) runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT) }
        }
        handle
    }

    private const val BACKGROUND_LOAD_PRIORITY = 5

    private fun hold() = synchronized(stateLock) { users++; unloadTask?.cancel(false) }

    /** A dictation (or retry) starts: loads if needed (blocks) and keeps the engine until [release]. */
    fun acquire(ctx: Context): Long {
        hold()
        // someone is waiting now: a low-priority background load in progress gets normal priority
        loaderTid.takeIf { it != 0 }?.let { tid ->
            runCatching { android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_DEFAULT) }
        }
        return load(ctx)
    }

    /** A text box is open (main thread, never blocks): loads in the background and keeps the engine until [release]. */
    fun acquireAsync(ctx: Context) {
        hold()
        if (handle == 0L) {
            val app = ctx.applicationContext
            Thread { load(app, background = true) }.start()
        }
    }

    /** The dictation is done (or the text box closed): the engine is freed after [IDLE_S] without another user. */
    fun release() = synchronized(stateLock) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            unloadTask?.cancel(false)
            unloadTask = timer.schedule({ unloadIfIdle() }, IDLE_S, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /**
     * Frees the engine now unless someone holds it. Waits for a load in progress, so call it off the main thread.
     * A user arriving meanwhile simply loads again after the free (its load() waits on [loadLock]).
     */
    fun unloadIfIdle() = synchronized(loadLock) {
        val idle = synchronized(stateLock) { users == 0 }
        if (idle && handle != 0L) {
            IvyNative.free(handle)
            handle = 0L
        }
    }

    // The rewrite model (Grammar + Formal) has its own lock, so loading it never blocks a dictation, and it stays in
    // memory only for REWRITER_IDLE_S after its last use: kept loaded, it pushed the speech model out to swap
    // (CMF Phone 1, 2026-11-09: 2.3 GB of Ivy swapped out, 10 s per 60 s dictation).
    private const val REWRITER_IDLE_S = 60L
    private val rwLock = Object()
    private var rewriterReady = false
    private val rwTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    private var rwUnload: java.util.concurrent.ScheduledFuture<*>? = null

    /** Under [rwLock]: loads the rewrite model if needed and restarts its idle countdown. False if there is none. */
    private fun rewriterLoaded(app: Context): Boolean {
        if (!rewriterReady) {
            val path = ModelStore.rewrite(app) ?: return false
            initBackends(app)
            rewriterReady = IvyNative.rewriteLoad(path)
            if (!rewriterReady) return false
        }
        rwUnload?.cancel(false)
        rwUnload = rwTimer.schedule({ freeRewriter() }, REWRITER_IDLE_S, java.util.concurrent.TimeUnit.SECONDS)
        return true
    }

    /** Frees the rewrite model now (waits for a rewrite in progress). */
    fun freeRewriter() = synchronized(rwLock) {
        if (rewriterReady) { IvyNative.rewriteFree(); rewriterReady = false }
    }

    /** Loads the rewrite model in the background (the tone slider just appeared), so Grammar/Formal answer quickly. */
    fun preloadRewriter(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            // below normal priority: right after a dictation the user is back in their app, which must not stutter
            runCatching { android.os.Process.setThreadPriority(BACKGROUND_LOAD_PRIORITY) }
            synchronized(rwLock) { runCatching { rewriterLoaded(app) } }
        }.start()
    }

    /** "Make it professional": the rewrite of [text], or null if this build has no rewrite model. Blocks. */
    fun rewrite(ctx: Context, text: String, grammar: Boolean = false): String? = synchronized(rwLock) {
        if (!rewriterLoaded(ctx.applicationContext)) return null
        if (grammar) IvyNative.rewriteMode(text.toByteArray(Charsets.UTF_8), 1) else IvyNative.rewrite(text.toByteArray(Charsets.UTF_8))
    }

}
