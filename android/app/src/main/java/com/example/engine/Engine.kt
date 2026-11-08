package com.example.engine

import android.content.Context

/**
 * The one engine the app and the bubble share (about 1.4 GB of memory while loaded). Loaded once, on a background
 * thread, and kept for as long as the process lives.
 */
object Engine {
    @Volatile var handle = 0L
        private set
    @Volatile var error = ""
        private set
    private val lock = Object()

    /** Blocks until the engine is ready (needs the downloaded model). Returns 0 on failure, see [error]. */
    fun load(ctx: Context): Long = synchronized(lock) {
        if (handle != 0L) return handle
        val app = ctx.applicationContext
        try {
            if (!ModelStore.ready(app)) { error = "The speech model is not downloaded yet"; return 0L }
            IvyNative.initBackends(app.applicationInfo.nativeLibraryDir)
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
            handle = IvyNative.load(ModelStore.model(app), ModelStore.mmproj(app), 0)
            if (handle == 0L) error = IvyNative.lastError()
        } catch (e: Throwable) {
            error = e.toString()
        }
        // ponytail: kept loaded forever; unload after idle if low-memory phones start killing the service
        handle
    }

    @Volatile private var rewriterReady = false

    /** "Make it professional": the rewrite of [text], or null if this build has no rewrite model. Blocks. */
    fun rewrite(ctx: Context, text: String, grammar: Boolean = false): String? = synchronized(lock) {
        if (!rewriterReady) {
            val app = ctx.applicationContext
            val path = ModelStore.rewrite(app) ?: return null
            IvyNative.initBackends(app.applicationInfo.nativeLibraryDir)
            rewriterReady = IvyNative.rewriteLoad(path)
            if (!rewriterReady) return null
        }
        if (grammar) IvyNative.rewriteMode(text.toByteArray(Charsets.UTF_8), 1) else IvyNative.rewrite(text.toByteArray(Charsets.UTF_8))
    }

    /** Starts loading in the background if it isn't loaded yet. */
    fun preload(ctx: Context) {
        if (handle == 0L) Thread { load(ctx) }.start()
    }
}
