package com.example.engine

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Ivy's three model files are downloaded once from Hugging Face into the app's private storage; after that Ivy
 * never needs the internet. A half-finished download resumes where it stopped.
 */
object ModelStore {
    const val MODEL = "s114-Q8_0.gguf"
    const val MMPROJ = "mmproj-s114-f16.gguf"
    /** "Make it professional" / Grammar (Qwen2.5-0.5B fine-tune). */
    const val REWRITE = "ivy-pro2-Q8_0.gguf"

    private const val BASE = "https://huggingface.co/raj7676/ivy-dictation/resolve/main/android/"
    /** Exact byte sizes: a file counts as installed only when it matches. */
    private val SIZES = linkedMapOf(MODEL to 639_442_848L, MMPROJ to 378_575_456L, REWRITE to 531_067_808L)
    val totalBytes = SIZES.values.sum()

    private val http by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

    fun dir(ctx: Context) = File(ctx.filesDir, "models")

    fun ready(ctx: Context) = SIZES.all { (n, size) -> File(dir(ctx), n).let { it.exists() && it.length() == size } }

    /** Downloads any missing file. [progress] gets 0..1. Throws IOException when offline or the server fails. */
    fun install(ctx: Context, progress: (Float) -> Unit) {
        val d = dir(ctx).apply { mkdirs() }
        var done = SIZES.entries.sumOf { (n, size) -> if (File(d, n).length() == size) size else 0L }
        progress(done / totalBytes.toFloat())
        for ((name, size) in SIZES) {
            val out = File(d, name)
            if (out.exists() && out.length() == size) continue
            val part = File(d, "$name.part")
            if (part.length() > size) part.delete()
            var have = part.length()
            done += have
            val req = Request.Builder().url(BASE + name).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
                if (have > 0 && r.code != 206) { done -= have; part.delete(); have = 0 } // server ignored Range: start over
                RandomAccessFile(part, "rw").use { f ->
                    f.seek(have)
                    val buf = ByteArray(1 shl 17)
                    var last = 0L
                    r.body!!.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            f.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - last > 100_000_000L) { last = now; progress(done / totalBytes.toFloat()) }
                        }
                    }
                }
            }
            if (part.length() != size) throw IOException("Download incomplete, tap retry")
            if (!part.renameTo(out)) throw IOException("Could not save $name")
        }
        progress(1f)
    }

    fun model(ctx: Context) = File(dir(ctx), MODEL).absolutePath
    fun mmproj(ctx: Context) = File(dir(ctx), MMPROJ).absolutePath
    fun rewrite(ctx: Context): String? = File(dir(ctx), REWRITE).takeIf { it.exists() }?.absolutePath
}
