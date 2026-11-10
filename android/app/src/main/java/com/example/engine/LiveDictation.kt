package com.example.engine

import org.json.JSONObject

/** Result of one dictation: the text and where the time went (ms after the user let go). */
data class DictationResult(
    val text: String,
    val afterReleaseMs: Long,
    val readMs: Long,
    val writeMs: Long,
    val windowsReused: Int,
    val windowsAtEnd: Int,
    val tokens: Int,
    val note: String,
    val audioSecs: Float,
    val maxWindowMs: Long,
    val drafts: Int = 0,
    val proposed: Int = 0,
    val accepted: Int = 0,
) {
    companion object {
        fun parse(json: String, afterReleaseMs: Long, audioSecs: Float, maxWindowMs: Long): DictationResult {
            val j = JSONObject(json)
            return DictationResult(j.getString("text"), afterReleaseMs, j.getLong("read_ms"), j.getLong("write_ms"),
                j.getInt("reused"), j.getInt("at_end"), j.getInt("tokens"), j.getString("note"), audioSecs, maxWindowMs,
                j.optInt("drafts"), j.optInt("proposed"), j.optInt("accepted"))
        }
    }
}

/**
 * Live reading while the user talks: a worker thread reads every finished 8 s window into the model as soon as it
 * exists and, in between, drafts the text so far; after release only the last few seconds are read and the draft is
 * checked in batches. One dictation at a time.
 */
class LiveDictation(private val loadEngine: () -> Long, private val dictionary: Array<String> = emptyArray()) {
    private val mic = MicRecorder()
    private var worker: Thread? = null
    @Volatile private var recording = false
    // set by cancel(): from then on this dictation's worker leaves the engine alone (the next dictation may own it)
    @Volatile private var cancelled = false
    @Volatile private var maxStepMs = 0L
    // 0 until loaded: the mic starts at once and the engine loads while the user talks (it is freed when idle)
    @Volatile private var engine = 0L

    /** The finished recording (16 kHz mono), for saving to Recordings/Ivy Dictation. */
    var lastAudio: FloatArray? = null
        private set

    fun start() {
        maxStepMs = 0
        mic.start()
        recording = true
        worker = Thread {
            // normal priority: the bubble's animation must win over reading/drafting (see Engine.load)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            val h = loadEngine()
            if (h == 0L || cancelled) return@Thread
            // One dictation at a time in the engine. The bubble is idle again as soon as Cancel is tapped, so the
            // next dictation can start while the cancelled one's worker is still inside its last step: it waits here
            // (its microphone is already recording).
            synchronized(oneAtATime) {
                if (cancelled) return@Thread
                IvyNative.liveReset(h, dictionary)
                engine = h
                while (recording) {
                    if (mic.size < 8000) { Thread.sleep(50); continue }
                    val snap = mic.snapshot()
                    val t = System.nanoTime()
                    if (IvyNative.liveStep(h, snap, snap.size)) {
                        maxStepMs = maxOf(maxStepMs, (System.nanoTime() - t) / 1_000_000)
                    } else {
                        Thread.sleep(150)
                    }
                }
            }
        }.apply { start() }
    }

    /** Mic loudness 0..1 while recording. */
    val level: Float get() = mic.level

    /** Throws the recording away (no text). Waits for the worker, and so for an engine load in progress. */
    fun cancel() {
        cancelled = true
        mic.stop()
        // interrupt before the worker is let out of its loop: the next dictation's worker enters the engine only
        // after this one has left, so this interrupt can never land on the next dictation
        engine.let { if (it != 0L) IvyNative.liveInterrupt(it) }
        recording = false
        worker?.join()
    }

    /** Called when the user lets go. Blocks until the text is ready (and the engine, if it was still loading). */
    fun finish(): DictationResult {
        val released = System.nanoTime()
        val pcm = mic.stop()
        lastAudio = pcm
        recording = false
        engine.let { if (it != 0L) IvyNative.liveInterrupt(it) } // a draft in progress gives way now
        worker?.join()
        val h = engine
        if (h == 0L) error("engine not ready: ${Engine.error}")
        val json = IvyNative.liveFinish(h, pcm, pcm.size)
        val ms = (System.nanoTime() - released) / 1_000_000
        return DictationResult.parse(json, ms, pcm.size / 16000f, maxStepMs)
    }

    private companion object {
        val oneAtATime = Any()
    }
}
