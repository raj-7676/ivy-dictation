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
class LiveDictation(private val engine: Long, private val dictionary: Array<String> = emptyArray()) {
    private val mic = MicRecorder()
    private var worker: Thread? = null
    @Volatile private var recording = false
    @Volatile private var maxStepMs = 0L

    /** The finished recording (16 kHz mono), for saving to Recordings/Ivy Dictation. */
    var lastAudio: FloatArray? = null
        private set

    fun start() {
        IvyNative.liveReset(engine, dictionary)
        maxStepMs = 0
        mic.start()
        recording = true
        worker = Thread {
            // the engine's own threads copy this priority: Brave and the system must not steal its cores
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (recording) {
                if (mic.size < 8000) { Thread.sleep(50); continue }
                val snap = mic.snapshot()
                val t = System.nanoTime()
                if (IvyNative.liveStep(engine, snap, snap.size)) {
                    maxStepMs = maxOf(maxStepMs, (System.nanoTime() - t) / 1_000_000)
                } else {
                    Thread.sleep(150)
                }
            }
        }.apply { start() }
    }

    /** Mic loudness 0..1 while recording. */
    val level: Float get() = mic.level

    /** Throws the recording away (no text). */
    fun cancel() {
        mic.stop()
        recording = false
        IvyNative.liveInterrupt(engine)
        worker?.join()
    }

    /** Called when the user lets go. Blocks until the text is ready. */
    fun finish(): DictationResult {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val released = System.nanoTime()
        val pcm = mic.stop()
        lastAudio = pcm
        recording = false
        IvyNative.liveInterrupt(engine) // a draft in progress gives way now; a window being read finishes first
        worker?.join()
        val json = IvyNative.liveFinish(engine, pcm, pcm.size)
        val ms = (System.nanoTime() - released) / 1_000_000
        return DictationResult.parse(json, ms, pcm.size / 16000f, maxStepMs)
    }
}
