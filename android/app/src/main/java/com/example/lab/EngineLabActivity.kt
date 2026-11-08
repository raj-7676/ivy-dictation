package com.example.lab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.engine.DictationResult
import com.example.engine.IvyNative
import com.example.engine.LiveDictation
import com.example.engine.ModelStore
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.IvyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test screen for the phone engine (separate launcher icon "Ivy Engine Lab"): installs the model, times every
 * setup on fixed clips (10 / 30 / 60 s), and lets you dictate for real. Results can be copied and sent back.
 */
class EngineLabActivity : ComponentActivity() {
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        // adb: am start -n com.glowco.ivy/com.example.lab.EngineLabActivity --ez autorun true
        setContent { IvyTheme { LabScreen(this, intent.getBooleanExtra("autorun", false)) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Lab.unload()
    }
}

private object Lab {
    var handle = 0L
    var threads = 0

    private var backends = ""

    fun load(ctx: Context, t: Int): String {
        unload()
        if (backends.isEmpty()) {
            IvyNative.initBackends(ctx.applicationInfo.nativeLibraryDir)
            backends = IvyNative.cpuInfo()
        }
        val t0 = System.nanoTime()
        handle = IvyNative.load(ModelStore.model(ctx), ModelStore.mmproj(ctx), t)
        threads = t
        val ms = (System.nanoTime() - t0) / 1_000_000
        return if (handle == 0L) "load FAILED: ${IvyNative.lastError()}"
        else "engine loaded (threads ${label(t)} = ${IvyNative.threads(handle)}, cores ${Runtime.getRuntime().availableProcessors()}) in $ms ms · $backends"
    }

    fun unload() {
        if (handle != 0L) IvyNative.free(handle)
        handle = 0
    }

    fun label(t: Int) = if (t <= 0) "auto" else "$t"
}

private class Clip(val name: String, val secs: Float, val ref: String, val pcm: FloatArray)

private fun loadClips(ctx: Context): List<Clip> {
    val meta = JSONArray(ctx.assets.open("testclips/clips.json").bufferedReader().readText())
    return (0 until meta.length()).map { i ->
        val m = meta.getJSONObject(i)
        val bytes = ctx.assets.open("testclips/${m.getString("file")}").readBytes()
        val bb = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val pcm = FloatArray(bb.remaining()) { bb.get(it) / 32768f }
        Clip(m.getString("file"), m.getDouble("secs").toFloat(), m.getString("ref"), pcm)
    }
}

/** Word error rate after lowercasing and dropping punctuation (rough check that the engine hears right). */
private fun wer(ref: String, hyp: String): Float {
    fun words(s: String) = s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(" ").filter { it.isNotBlank() }
    val r = words(ref)
    val h = words(hyp)
    var prev = IntArray(h.size + 1) { it }
    for (i in 1..r.size) {
        val cur = IntArray(h.size + 1)
        cur[0] = i
        for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
        prev = cur
    }
    return if (r.isEmpty()) 0f else prev[h.size] * 100f / r.size
}

/**
 * Live reading on a clip as if it were spoken: the audio "arrives" in real time, the engine reads windows and writes
 * drafts as it would while you talk, and the wait is timed from the moment the clip ends (including any step still
 * running then).
 */
private fun simulateLive(h: Long, pcm: FloatArray): DictationResult {
    IvyNative.liveReset(h, emptyArray())
    var maxStep = 0L
    val start = System.nanoTime()
    val end = start + pcm.size * 1_000_000_000L / 16000
    val release = Thread { Thread.sleep(maxOf(0L, (end - System.nanoTime()) / 1_000_000)); IvyNative.liveInterrupt(h) }
    release.start()
    while (true) {
        val n = minOf(pcm.size.toLong(), (System.nanoTime() - start) * 16000 / 1_000_000_000L).toInt()
        if (n >= pcm.size) break
        if (n < 8000) { Thread.sleep(50); continue }
        val t = System.nanoTime()
        if (IvyNative.liveStep(h, pcm, n)) maxStep = maxOf(maxStep, (System.nanoTime() - t) / 1_000_000)
        else Thread.sleep(150)
    }
    release.join()
    val json = IvyNative.liveFinish(h, pcm, pcm.size)
    return DictationResult.parse(json, (System.nanoTime() - end) / 1_000_000, pcm.size / 16000f, maxStep)
}

private fun once(h: Long, pcm: FloatArray): DictationResult {
    val t = System.nanoTime()
    val json = IvyNative.transcribe(h, pcm, pcm.size, emptyArray())
    return DictationResult.parse(json, (System.nanoTime() - t) / 1_000_000, pcm.size / 16000f, 0)
}

private fun s(ms: Long) = "%.2f s".format(ms / 1000f)

@Composable
private fun LabScreen(ctx: Context, autorun: Boolean) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("Ivy Engine Lab · model s114 (Q8_0) · device ${android.os.Build.MODEL}\n") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(if (ModelStore.ready(ctx)) 1f else 0f) }
    var threads by remember { mutableStateOf(0) }
    var talking by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<LiveDictation?>(null) }
    var lastText by remember { mutableStateOf("") }
    // every line also goes to files/lab_results.txt (adb: run-as com.glowco.ivy cat files/lab_results.txt)
    fun say(line: String) {
        log += line + "\n"
        runCatching { java.io.File(ctx.filesDir, "lab_results.txt").appendText(line + "\n") }
    }

    fun run(block: suspend CoroutineScope.() -> Unit) {
        if (busy) return
        busy = true
        scope.launch(Dispatchers.Default) {
            try { block() } catch (e: Throwable) { say("ERROR: $e") }
            busy = false
        }
    }

    fun ensureEngine(): Boolean {
        if (progress < 1f) {
            say("Installing model files (first start only)...")
            ModelStore.install(ctx) { progress = it }
            say("Model installed.")
        }
        if (Lab.handle == 0L || Lab.threads != threads) say(Lab.load(ctx, threads))
        return Lab.handle != 0L
    }

    // Real-time speed test: auto threads, 4, and 2 (2 = rough stand-in for a budget phone). ~6 min.
    fun speedTest() = run {
        val clips = loadClips(ctx)
        say("\n=== Speed test, real time (wait = after the clip ends; step = longest piece of work while talking)")
        for (t in listOf(0, 4, 2)) {
            threads = t
            if (!ensureEngine()) break
            once(Lab.handle, clips[0].pcm) // warm-up
            for (c in clips) {
                val l = simulateLive(Lab.handle, c.pcm)
                say("t=${Lab.label(t)} ${"%.0f".format(c.secs)}s: WAIT ${s(l.afterReleaseMs)} " +
                    "(read ${s(l.readMs)} write ${s(l.writeMs)}, ${l.tokens} tok, drafts ${l.drafts}, " +
                    "kept ${l.accepted}/${l.proposed}, step ${s(l.maxWindowMs)}) | wer ${"%.1f".format(wer(c.ref, l.text))}%")
            }
        }
        say("=== done")
    }
    LaunchedEffect(Unit) { if (autorun) speedTest() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0F0C15)).safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Ivy Engine Lab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (progress < 1f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = IvyOrange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Threads", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            for (t in listOf(0, 2, 4, 6)) {
                FilterChip(selected = threads == t, onClick = { threads = t }, label = { Text(Lab.label(t)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = IvyOrange), onClick = {
                speedTest()
            }) { Text("Run speed test") }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Ivy Engine Lab", log))
            }) { Text("Copy results") }
        }
        // Hold to talk: real microphone, live reading, the same path the bubble will use.
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(32.dp))
                .background(if (talking) IvyOrange else Color(0xFF2A2433))
                .pointerInput(busy, threads) {
                    detectTapGestures(onPress = {
                        if (busy) return@detectTapGestures
                        val ok = withContext(Dispatchers.Default) { ensureEngine() }
                        if (!ok) return@detectTapGestures
                        val d = LiveDictation(Lab.handle)
                        live = d
                        d.start()
                        talking = true
                        tryAwaitRelease()
                        talking = false
                        val r = withContext(Dispatchers.Default) { d.finish() }
                        live = null
                        lastText = r.text
                        say("TALK ${"%.1f".format(r.audioSecs)}s: after release ${s(r.afterReleaseMs)} (read ${s(r.readMs)} " +
                            "write ${s(r.writeMs)}, ${r.tokens} tok, windows ${r.windowsReused}+${r.windowsAtEnd}, " +
                            "drafts ${r.drafts}, kept ${r.accepted}/${r.proposed}, longest step ${s(r.maxWindowMs)})" +
                            "${if (r.note.isNotEmpty()) " note: ${r.note}" else ""}\n  > ${r.text}")
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            Text(if (talking) "Listening... let go to finish" else "Hold to talk", color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold)
        }
        if (lastText.isNotEmpty()) {
            SelectionContainer { Text(lastText, color = Color.White, fontSize = 16.sp) }
        }
        if (busy) Text("Working...", color = IvyOrange, fontSize = 13.sp)
        SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(log, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
