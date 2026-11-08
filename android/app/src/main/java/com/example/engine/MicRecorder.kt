package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** 16 kHz mono float microphone recording. VOICE_RECOGNITION = no automatic gain or noise suppression. */
class MicRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var buf = FloatArray(16000 * 30)
    @Volatile var size = 0
        private set
    /** Loudness of the last 0.1 s (peak, 0..1), for the bubble's waveform. */
    @Volatile var level = 0f
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(min, 16000 * 4))
        record = r
        size = 0
        running = true
        r.startRecording()
        thread = Thread {
            val chunk = FloatArray(1600)
            while (running) {
                val n = r.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                if (n > 0) append(chunk, n)
            }
        }.apply { start() }
    }

    @Synchronized private fun append(c: FloatArray, n: Int) {
        var peak = 0f
        for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(c[i]))
        level = peak
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
        System.arraycopy(c, 0, buf, size, n)
        size += n
    }

    /** A copy of everything recorded so far. */
    @Synchronized fun snapshot(): FloatArray = buf.copyOf(size)

    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        record?.run { stop(); release() }
        record = null
        return snapshot()
    }
}
