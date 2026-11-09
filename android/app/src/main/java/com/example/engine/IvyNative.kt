package com.example.engine

/** The on-device speech model (s114, Qwen3-ASR-0.6B fine-tune) through llama.cpp: native/ivy_engine.cpp. */
object IvyNative {
    init {
        System.loadLibrary("ivy")
    }

    /**
     * Loads the CPU code once. Every ARM level is in the APK (libggml-cpu-android_*.so, plain ARMv8.0 up to
     * ARMv9 + SME). The newest level is not always the fastest (S26: 2.5x slower audio reading), so the first start
     * times each level this chip supports and keeps the fastest in [cacheDir]/cpu_code.txt. Use [Engine.initBackends].
     */
    external fun initBackends(libDir: String, cacheDir: String)

    /** Which compute backends/CPU code were picked, for the test log. */
    external fun cpuInfo(): String

    /** Returns an engine handle, or 0 on failure (see [lastError]). threads <= 0 picks automatically. */
    external fun load(model: String, mmproj: String, threads: Int): Long
    external fun lastError(): String
    external fun free(handle: Long)

    /** Start a dictation. The system prompt stays in memory between dictations. */
    external fun liveReset(handle: Long, dictionary: Array<String>)

    /** The recording so far (16 kHz mono). Reads at most one finished 8 s window, or writes a draft of the text so far; true if it did any work. */
    external fun liveStep(handle: Long, pcm: FloatArray, n: Int): Boolean

    /** Any thread: the speaker let go; a draft in progress stops at once. Call before joining the worker. */
    external fun liveInterrupt(handle: Long)

    /** Threads the engine really uses (auto = the fast cores, at most 6). */
    external fun threads(handle: Long): Int

    /** After release: reads what is left and writes the text. JSON: text, total_ms, read_ms, write_ms, ... */
    external fun liveFinish(handle: Long, pcm: FloatArray, n: Int): String

    /** "Make it professional": loads the small rewrite model (once). */
    external fun rewriteLoad(path: String): Boolean

    /** Frees the rewrite model; the next [rewriteLoad] loads it again. */
    external fun rewriteFree()

    /** The professional version of [utf8] text (never with an em or en dash); the input unchanged if it fails. */
    external fun rewrite(utf8: ByteArray): String

    /** [rewrite] with a choice of task: mode 0 = make it professional, mode 1 = fix the grammar (keeps the casual voice). */
    external fun rewriteMode(utf8: ByteArray, mode: Int): String

    /** No live reading: the whole clip at once (baseline). Same JSON as [liveFinish]. */
    external fun transcribe(handle: Long, pcm: FloatArray, n: Int, dictionary: Array<String>): String
}
