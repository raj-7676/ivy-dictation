package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Each dictation's audio as a normal WAV in the phone's Recordings/Ivy Dictation folder (Files app > Recordings >
 * Ivy Dictation), so people can keep or share their own recordings. Removed together with its History entry.
 */
object AudioFiles {
    const val FOLDER = "Ivy Dictation"
    private const val SR = 16000

    /** Where the files are, in words, for the History screen. */
    val whereText: String
        get() = if (Build.VERSION.SDK_INT >= 31) "Files › Recordings › $FOLDER" else "Files › Music › $FOLDER"

    /** Saves 16 kHz mono audio; returns the file's URI, or null (old Android or no space). */
    fun save(ctx: Context, pcm: FloatArray, at: Long): String? {
        if (Build.VERSION.SDK_INT < 29 || pcm.isEmpty()) return null
        return try {
            val dir = if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC
            val name = "Ivy " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date(at)) + ".wav"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "$dir/$FOLDER")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
            cr.openOutputStream(uri)?.use { it.write(wav(pcm)) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            uri.toString()
        } catch (_: Throwable) {
            null
        }
    }

    /** False if the file is already gone (deleted in the Files app). */
    fun exists(ctx: Context, uri: String?): Boolean = uri != null &&
        runCatching { ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false }.getOrDefault(false)

    fun delete(ctx: Context, uri: String?) {
        if (uri == null) return
        runCatching { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    /** The recording back as 16 kHz mono floats (for Retry). */
    fun read(ctx: Context, uri: String): FloatArray? = runCatching {
        val bytes = ctx.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FloatArray(bb.remaining()) { bb.get(it) / 32768f }
    }.getOrNull()

    private fun wav(pcm: FloatArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (x in pcm) data.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.size * 2)
        return ByteArrayOutputStream().apply { write(h.array()); write(data.array()) }.toByteArray()
    }
}
