package com.example.ui.onboarding

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.rounded.History
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.Permissions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.IvyLogoMark
import com.example.ui.dictionary.DictionaryViewModel
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassFillActive
import com.example.ui.theme.InterFamily
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * First-run setup, built for Android (no hotkeys): learn how the bubble works (tap a text box -> bubble -> talk ->
 * text), grant the three permissions, download the model, optionally teach Ivy words and snippets, then a short
 * practice. Steps slide and fade; one orange primary button at the bottom; optional steps can be skipped.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    dictionaryViewModel: DictionaryViewModel,
    onFinish: () -> Unit
) {
    val ui by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // "Allow all": the mic prompt, then Android's overlay screen, then the accessibility screen, one after another.
    // The states are re-read from Android every time Ivy comes back to the front.
    var chain by remember { mutableStateOf(false) }
    var askNext by remember { mutableStateOf(0) }
    var lastAsked by remember { mutableStateOf("") }
    fun readPermissions() = viewModel.setPermissions(Permissions.mic(context), Permissions.overlay(context), Permissions.accessibility(context))
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { readPermissions() }
    // Asks for the first missing permission. If the same one is still missing when Ivy comes back (the user backed
    // out), the chain stops instead of nagging; tapping "Allow all" again starts over.
    fun nextPermission() {
        val missing = when {
            !Permissions.mic(context) -> "mic"
            !Permissions.overlay(context) -> "overlay"
            !Permissions.accessibility(context) -> "access"
            else -> ""
        }
        if (missing.isEmpty() || missing == lastAsked) { chain = false; return }
        lastAsked = missing
        when (missing) {
            "mic" -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            "overlay" -> context.startActivity(Permissions.overlayIntent(context))
            else -> context.startActivity(Permissions.accessibilityIntent())
        }
    }
    LaunchedEffect(askNext) { if (askNext > 0) nextPermission() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                readPermissions()
                if (chain) askNext++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val steps = SetupStep.entries
    val index = steps.indexOf(ui.step)
    val progress by animateFloatAsState((index + 1f) / steps.size, tween(450, easing = FastOutSlowInEasing), label = "progress")
    val optional = ui.step == SetupStep.WORDS || ui.step == SetupStep.SNIPPETS
    var welcomeDone by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 24.dp)
    ) {
        // Top: back, progress line, skip (optional steps only)
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(56.dp)) {
                if (index > 0) {
                    Text("Back", fontFamily = InterFamily, fontSize = 14.sp, color = TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.back() }.padding(6.dp))
                }
            }
            Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(GlassFillActive)) {
                Box(Modifier.fillMaxWidth(progress).height(4.dp).clip(CircleShape).background(IvyOrange))
            }
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                if (optional) {
                    Text("Skip", fontFamily = InterFamily, fontSize = 14.sp, color = TextSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.next() }.padding(6.dp))
                }
            }
        }

        AnimatedContent(
            targetState = ui.step,
            transitionSpec = {
                val dir = if (ui.goingForward) 1 else -1
                (slideInHorizontally(tween(380, easing = FastOutSlowInEasing)) { it / 5 * dir } + fadeIn(tween(320))) togetherWith
                    (slideOutHorizontally(tween(300)) { -it / 5 * dir } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "setup-step"
        ) { step ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (step) {
                    SetupStep.WELCOME -> Welcome(onFinished = { welcomeDone = true })
                    SetupStep.HOW_IT_WORKS -> HowItWorks()
                    SetupStep.POLISH -> PolishDemo()
                    SetupStep.PERMISSIONS -> Permissions(ui.micGranted, ui.overlayGranted, ui.accessibilityGranted)
                    SetupStep.DOWNLOAD -> Download(ui.downloadProgressMb, ui.totalModelMb, ui.isDownloadComplete, ui.downloadError)
                    SetupStep.WORDS -> Words(dictionaryViewModel)
                    SetupStep.SNIPPETS -> Snippets(dictionaryViewModel)
                    SetupStep.PRACTICE -> Practice(ui.practicePhase, ui.practiceText, viewModel::practiceTapBox, viewModel::practiceTapBubble)
                }
            }
        }

        // Bottom: one primary action
        val (label, enabled, action) = when (ui.step) {
            SetupStep.WELCOME -> Triple("Get started", true, viewModel::next)
            SetupStep.PERMISSIONS -> if (ui.micGranted && ui.overlayGranted && ui.accessibilityGranted)
                Triple("Continue", true, viewModel::next) else Triple("Allow all", true, { chain = true; lastAsked = ""; nextPermission() })
            SetupStep.DOWNLOAD -> when {
                ui.isDownloadComplete -> Triple("Continue", true, viewModel::next)
                ui.downloadError.isNotEmpty() -> Triple("Retry", true, viewModel::startDownload)
                else -> Triple("Downloading…", false, viewModel::next)
            }
            SetupStep.PRACTICE -> Triple(if (ui.practicePhase == PracticePhase.DONE) "Start using Ivy" else "Skip practice", true, onFinish)
            else -> Triple("Continue", true, viewModel::next)
        }
        // On the welcome story the button waits for the story to end, then rises in (space is kept so nothing jumps).
        val show = remember { Animatable(0f) }
        LaunchedEffect(ui.step, welcomeDone) {
            if (ui.step != SetupStep.WELCOME) show.snapTo(1f)
            else if (welcomeDone && show.value < 1f) {
                show.animateTo(1f, tween(800, easing = ExpoOut))
            } else show.snapTo(0f)
        }
        Box(Modifier.graphicsLayer { alpha = show.value; translationY = 40f * (1f - show.value) }) {
            PrimaryButton(label, enabled && show.value > 0.5f, action)
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (enabled) IvyOrange else GlassFillActive, label = "btn")
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).clip(CircleShape).background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnimatedContent(targetState = label, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "btn-label") { l ->
            Text(l, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                color = if (enabled) Color.White else TextMuted)
        }
        if (enabled) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Title(text: String, sub: String) {
    Text(text, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp,
        letterSpacing = (-0.6).sp, color = TextPrimary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(12.dp))
    Text(sub, fontFamily = InterFamily, fontSize = 15.sp, lineHeight = 22.sp, color = TextSecondary, textAlign = TextAlign.Center)
}

/** Fades and lifts its content in shortly after the step appears. */
@Composable
private fun Rise(delayMs: Int = 0, content: @Composable () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); a.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
    Box(Modifier.alpha(a.value).padding(top = (12 * (1 - a.value)).dp)) { content() }
}

// ---------- steps ----------

// ---------- Welcome: a ~6.5 s motion piece driven by one timeline, like a video edit ----------
//
// One clock (t, in ms) runs 0 -> END linearly; every element reads its own keyframed progress from it, so moves
// overlap and hand over to each other with no dead pauses. Words enter one by one (fade + rise + focus pull),
// a whole beat leaves while the next is already arriving, and the piece settles on the Ivy mark and stays.

private const val WELCOME_END = 6800f
private val ExpoOut = androidx.compose.animation.core.CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
private val SoftInOut = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/** Progress 0..1 of a keyframed segment starting at [start] ms and lasting [dur] ms on the clock [t]. */
private fun seg(t: Float, start: Float, dur: Float, easing: androidx.compose.animation.core.Easing = ExpoOut): Float =
    easing.transform(((t - start) / dur).coerceIn(0f, 1f))

@Composable
private fun Welcome(onFinished: () -> Unit) {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // Start only after the app's logo intro (IntroSplash) has finished, so the two never overlap.
        androidx.compose.runtime.snapshotFlow { com.example.ui.components.AppIntro.finished }.first { it }
        delay(600) // the intro's 350 ms fade-out, then a short breath
        clock.animateTo(WELCOME_END, tween(WELCOME_END.toInt(), easing = LinearEasing))
        onFinished()
    }
    val t = clock.value

    Box(Modifier.fillMaxWidth().height(420.dp), contentAlignment = Alignment.Center) {
        // Beat 1 (0.2 - 3.1 s): "Every word you say / stays yours."
        val out1 = seg(t, 2500f, 800f, SoftInOut)
        if (out1 < 1f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - out1; translationY = -60f * out1; scaleX = 1f - 0.04f * out1; scaleY = scaleX
                }
            ) {
                WordLine("Every word you say", t, start = 200f, stagger = 85f, size = 44)
                WordLine("stays yours.", t, start = 950f, stagger = 140f, size = 44, accentLast = true)
            }
        }
        // Beat 2 (2.8 - 4.9 s): three short promises, stacked, arriving one after another
        val out2 = seg(t, 4500f, 750f, SoftInOut)
        if (t > 2700f && out2 < 1f) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.graphicsLayer { alpha = 1f - out2; translationY = -50f * out2 }
            ) {
                WordLine("No cloud.", t, start = 2850f, stagger = 70f, size = 31)
                WordLine("No tracking.", t, start = 3150f, stagger = 70f, size = 31)
                WordLine("No training on your voice.", t, start = 3450f, stagger = 60f, size = 31)
            }
        }
        // Beat 3 (4.7 s ->): mark, app name, then the promise further down; stays on screen
        if (t > 4600f) {
            val m = seg(t, 4700f, 1300f)
            val name = seg(t, 5150f, 1000f)
            val line = seg(t, 5650f, 1000f)
            val breathe = rememberInfiniteTransition(label = "breathe")
            val glow by breathe.animateFloat(0.92f, 1.04f, infiniteRepeatable(tween(3000, easing = SoftInOut), RepeatMode.Reverse), label = "glow")
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(220.dp).graphicsLayer { alpha = m; scaleX = (0.6f + 0.4f * m) * glow; scaleY = scaleX }) {
                        drawCircle(Brush.radialGradient(listOf(IvyOrange.copy(alpha = 0.34f), Color.Transparent)))
                    }
                    IvyLogoMark(
                        size = 88.dp,
                        modifier = Modifier.graphicsLayer {
                            alpha = m; scaleX = 0.82f + 0.18f * m; scaleY = scaleX; translationY = 24f * (1f - m)
                        }.blur((10f * (1f - m)).dp)
                    )
                }
                Text(
                    "Ivy Dictation", fontFamily = com.example.ui.theme.SyneFontFamily, fontWeight = FontWeight.ExtraBold,
                    fontSize = 32.sp, color = TextPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 22.dp).graphicsLayer { alpha = name; translationY = 20f * (1f - name) }
                        .blur((6f * (1f - name)).dp)
                )
                Text(
                    "Private dictation that\nnever leaves your phone.",
                    fontFamily = InterFamily, fontSize = 17.sp, lineHeight = 25.sp,
                    color = TextSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 30.dp).graphicsLayer { alpha = line; translationY = 18f * (1f - line) }
                        .blur((6f * (1f - line)).dp)
                )
            }
        }
    }
}


/**
 * A centred serif line whose words arrive one after another: each fades in, rises a little and comes into
 * focus. [accentLast] sets the last word in orange italic.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordLine(text: String, t: Float, start: Float, stagger: Float, size: Int, accentLast: Boolean = false) {
    val words = text.split(" ")
    // One line, never wrapped: each line is sized to fit the screen.
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        words.forEachIndexed { i, w ->
            val p = seg(t, start + i * stagger, 650f)
            val accent = accentLast && i == words.lastIndex
            Text(
                text = if (i < words.lastIndex) "$w " else w,
                fontFamily = com.example.ui.theme.InstrumentSerifFamily,
                fontStyle = if (accent) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                fontSize = size.sp, lineHeight = (size * 1.15f).sp, letterSpacing = (0.035f * size).sp, // the serif is narrow; open it up a little
                color = if (accent) IvyOrange else TextPrimary,
                maxLines = 1, softWrap = false,
                modifier = Modifier
                    .graphicsLayer { alpha = p; translationY = 26f * (1f - p) }
                    .blur((8f * (1f - p)).dp)
            )
        }
    }
}

/** Looping demo of the whole idea: tap a text box, the bubble appears, talk, the words appear. */
@Composable
private fun HowItWorks() {
    var phase by remember { mutableIntStateOf(0) } // 0 box idle, 1 tapped + bubble, 2 recording, 3 text, 4 bubble gone
    val sentence = "Running 10 minutes late, see you soon!"
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            phase = 0; typed = ""; delay(900)
            phase = 1; delay(1900)
            phase = 2; delay(1600)
            phase = 3
            for (n in 1..sentence.length) { typed = sentence.take(n); delay(28) }
            delay(1300)
            phase = 4; delay(1200)
        }
    }
    val captions = listOf("Tap any text box", "Hold the bubble for half a second", "Now just talk", "Your words appear", "No text box? The bubble hides")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        // Mini phone screen
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(GlassFill).border(1.dp, GlassBorder, RoundedCornerShape(28.dp)).padding(18.dp)
        ) {
            ChatBubbleMock("Are you on your way?", fromMe = false)
            Spacer(Modifier.height(70.dp))
            Box(Modifier.fillMaxWidth()) {
                // the bubble, above the box on the right
                androidx.compose.animation.AnimatedVisibility(
                    visible = phase in 1..3,
                    enter = scaleIn(tween(260)) + fadeIn(),
                    exit = scaleOut(tween(220)) + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 64.dp)
                ) {
                    val ring by androidx.compose.animation.core.animateFloatAsState(
                        if (phase == 1) 1f else 0f,
                        if (phase == 1) tween(500, easing = androidx.compose.animation.core.LinearEasing) else tween(0), label = "demo-ring"
                    )
                    DemoBubble(recording = phase == 2, ring = ring)
                }
            }
            val focused = phase in 1..3
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassFillActive)
                    .border(1.5.dp, if (focused) IvyOrange else GlassBorder, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(if (typed.isEmpty()) "Message" else typed, fontFamily = InterFamily, fontSize = 14.sp,
                    color = if (typed.isEmpty()) TextMuted else TextPrimary)
            }
        }
        Spacer(Modifier.height(26.dp))
        AnimatedContent(targetState = phase, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "caption") { p ->
            Text(captions[p], fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Text("That's all. No buttons to learn, no keyboard to switch.", fontFamily = InterFamily, fontSize = 15.sp,
            color = TextSecondary, textAlign = TextAlign.Center)
    }
}

/** Step 2 of the intro: the tone slider that appears after every dictation. It plays by itself and can be tried. */
@Composable
private fun PolishDemo() {
    // real output of Ivy's own Grammar and Formal for this sentence
    val originals = listOf(
        "So the vendor didnt send the invoice yet, i will follow up with them and let you know by tomorrow.",
        "So the vendor didn't send the invoice yet, I'll follow up with them and let you know by tomorrow.",
        "The vendor has not sent the invoice yet; I will follow up with them and inform you by tomorrow."
    )
    val captions = listOf("After you talk, a slider appears", "Grammar fixes slips and keeps your voice", "Formal makes it sound professional", "Slide back any time to undo")
    var level by remember { mutableIntStateOf(0) }
    var step by remember { mutableIntStateOf(0) }
    var touched by remember { mutableStateOf(false) }
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        while (true) {
            level = 0; step = 0; delay(1900)
            level = 1; step = 1; delay(2300)
            level = 2; step = 2; delay(2400)
            level = 0; step = 3; delay(2200)
        }
    }
    val stops = listOf("Original", "Grammar", "Formal")
    val stopW = 92.dp
    val knobX by androidx.compose.animation.core.animateDpAsState(
        4.dp + stopW * level, androidx.compose.animation.core.spring(dampingRatio = 0.62f, stiffness = 320f), label = "demo-knob"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(GlassFill).border(1.dp, GlassBorder, RoundedCornerShape(28.dp)).padding(18.dp)
        ) {
            ChatBubbleMock("Any update on the vendor invoice?", fromMe = false)
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier.fillMaxWidth().heightIn(min = 92.dp).clip(RoundedCornerShape(20.dp)).background(GlassFillActive)
                    .border(1.5.dp, IvyOrange, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                AnimatedContent(targetState = level, transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(180)) }, label = "demo-text") { l ->
                    Text(originals[l], fontFamily = InterFamily, fontSize = 14.sp, lineHeight = 21.sp, color = TextPrimary)
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.align(Alignment.End)) {
                Box(
                    Modifier.width(stopW * 3 + 8.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF15121B))
                        .border(1.dp, GlassBorder, RoundedCornerShape(22.dp))
                ) {
                    stops.forEachIndexed { i, label ->
                        Box(
                            Modifier.offset(x = 4.dp + stopW * i).width(stopW).fillMaxHeight()
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { touched = true; level = i; step = if (i == 0) 3 else i },
                            contentAlignment = Alignment.Center
                        ) { Text(label, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextSecondary) }
                    }
                    Box(
                        Modifier.offset(x = knobX, y = 4.dp).width(stopW).height(36.dp).clip(RoundedCornerShape(18.dp)).background(IvyOrange),
                        contentAlignment = Alignment.Center
                    ) { Text(stops[level], fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color(0xFF1B0D04)) }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        AnimatedContent(targetState = step, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "demo-caption") { p ->
            Text(captions[p], fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChatBubbleMock(text: String, fromMe: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (fromMe) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text, fontFamily = InterFamily, fontSize = 13.sp, color = TextPrimary,
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(GlassFillActive).padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

/** The tap menu of the real bubble, drawn for the intro: History / Record / Cancel (Cancel only while recording). */
@Composable
private fun DemoMenu(cancelEnabled: Boolean, onHistory: () -> Unit, onRecord: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF15121B))
            .border(1.dp, IvyOrange.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(vertical = 4.dp)
    ) {
        com.example.ui.bubble.BubbleMenuItem("History", Icons.Rounded.History, true, 0, onHistory)
        com.example.ui.bubble.BubbleMenuItem("Record", Icons.Rounded.Mic, true, 1, onRecord)
        com.example.ui.bubble.BubbleMenuItem("Cancel", Icons.Rounded.Close, cancelEnabled, 2, onCancel)
    }
}

@Composable
private fun DemoBubble(recording: Boolean, ring: Float = 0f) {
    AnimatedContent(targetState = recording, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "demo-bubble") { rec ->
        if (!rec) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFF15121B)).border(1.dp, GlassBorder, CircleShape), contentAlignment = Alignment.Center) {
                IvyLogoMark(size = 26.dp)
                if (ring > 0f) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawArc(
                        color = IvyOrange, startAngle = -90f, sweepAngle = 360f * ring, useCenter = false,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
                        topLeft = androidx.compose.ui.geometry.Offset(1.25.dp.toPx(), 1.25.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size(size.width - 2.5.dp.toPx(), size.height - 2.5.dp.toPx())
                    )
                }
            }
        } else {
            Row(
                Modifier.height(40.dp).clip(CircleShape).background(Color(0xFF15121B)).border(1.dp, IvyOrange, CircleShape).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { Waveform(Modifier.width(64.dp).height(18.dp)) }
        }
    }
}

@Composable
private fun Waveform(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "wave")
    val ph by t.animateFloat(0f, 6.283f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "ph")
    Canvas(modifier) {
        val n = 9
        val gap = size.width / n
        for (i in 0 until n) {
            val h = size.height * (0.3f + 0.7f * kotlin.math.abs(kotlin.math.sin(ph + i * 0.7f)))
            drawLine(IvyOrange, Offset(gap * i + gap / 2, (size.height - h) / 2), Offset(gap * i + gap / 2, (size.height + h) / 2),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** All three permissions on one screen, one "Allow all" button; each row ticks as it is granted. */
@Composable
private fun Permissions(mic: Boolean, overlay: Boolean, access: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Title("Three quick permissions", "Ivy needs these to listen, float over your apps and type for you. All of it stays on this phone.")
        Spacer(Modifier.height(28.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PermissionRow(Icons.Rounded.Mic, "Microphone", "Hears you only while the bubble is recording", mic, 0)
            PermissionRow(Icons.Rounded.Layers, "Show over other apps", "Lets the bubble float next to any text box", overlay, 120)
            PermissionRow(Icons.Rounded.TouchApp, "Find text boxes and type", "Notices the box you tap and types your words into it", access, 240)
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, title: String, body: String, granted: Boolean, delayMs: Int) {
    Rise(delayMs) {
        val border by animateColorAsState(if (granted) IvyOrange.copy(alpha = 0.6f) else GlassBorder, tween(300), label = "pb")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassFill).border(1.dp, border, RoundedCornerShape(20.dp)).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(IvyOrange.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = IvyOrange, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text(body, fontFamily = InterFamily, fontSize = 13.sp, lineHeight = 18.sp, color = TextSecondary)
            }
            Spacer(Modifier.width(10.dp))
            AnimatedContent(targetState = granted, transitionSpec = { scaleIn(tween(250)) + fadeIn() togetherWith fadeOut(tween(100)) }, label = "pr") { ok ->
                Box(Modifier.size(26.dp).clip(CircleShape).background(if (ok) IvyOrange else Color.Transparent)
                    .border(1.5.dp, if (ok) IvyOrange else GlassBorder, CircleShape), contentAlignment = Alignment.Center) {
                    if (ok) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun Download(mb: Int, total: Int, done: Boolean, error: String) {
    val frac by animateFloatAsState(mb / total.toFloat(), tween(200), label = "dl")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 8.dp.toPx()
                drawArc(GlassFillActive, 0f, 360f, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w))
                drawArc(IvyOrange, -90f, 360f * frac, false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    style = Stroke(w, cap = StrokeCap.Round))
            }
            AnimatedContent(targetState = done, transitionSpec = { scaleIn() + fadeIn() togetherWith fadeOut() }, label = "dl-center") { d ->
                if (d) Icon(Icons.Rounded.Check, null, tint = IvyOrange, modifier = Modifier.size(56.dp))
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(frac * 100).toInt()}%", fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 32.sp, color = TextPrimary)
                    Text("$mb / $total MB", fontFamily = InterFamily, fontSize = 12.sp, color = TextMuted)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Title(if (done) "Ivy is ready" else "Getting Ivy ready",
            if (done) "The speech model is on your phone. From now on Ivy works with no internet at all."
            else if (error.isNotEmpty()) error
            else "Downloading the speech model once, about 1.5 GB. After this, Ivy never needs the internet.")
        if (!done) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Wifi, null, tint = TextMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text("Wi-Fi recommended", fontFamily = InterFamily, fontSize = 13.sp, color = TextMuted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Words(dictionary: DictionaryViewModel) {
    var input by remember { mutableStateOf("") }
    val added = remember { mutableStateListOf<String>() }
    val add = {
        val w = input.trim()
        if (w.isNotEmpty() && w !in added) {
            dictionary.onNewWordInputChanged(w); dictionary.addPersonalWord(); added += w
        }
        input = ""
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
        Title("Any words Ivy should know?", "Your name, people you message, your company, product names. Ivy will always spell them right.")
        Spacer(Modifier.height(24.dp))
        InputWithAdd(input, { input = it }, "Add a name or word", add)
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            added.forEach { w ->
                Rise {
                    Row(Modifier.clip(CircleShape).background(IvyOrange.copy(alpha = 0.16f)).border(1.dp, IvyOrange.copy(alpha = 0.5f), CircleShape)
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(w, fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.Close, "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp).clickable {
                            added -= w; dictionary.removePersonalWord(w)
                        })
                    }
                }
            }
        }
        if (added.isEmpty()) {
            Text("You can always add more later in Dictionary.", fontFamily = InterFamily, fontSize = 13.sp, color = TextMuted)
        }
    }
}

@Composable
private fun Snippets(dictionary: DictionaryViewModel) {
    var trigger by remember { mutableStateOf("") }
    var expansion by remember { mutableStateOf("") }
    val added = remember { mutableStateListOf<Pair<String, String>>() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.verticalScroll(rememberScrollState())) {
        Title("Want a few shortcuts?", "Say a short phrase, Ivy types the long version. Say \"my email\" and your address appears.")
        Spacer(Modifier.height(24.dp))
        Field(trigger, { trigger = it }, "When I say… (e.g. my email)")
        Spacer(Modifier.height(10.dp))
        Field(expansion, { expansion = it }, "Type this… (e.g. you@example.com)")
        Spacer(Modifier.height(12.dp))
        val canAdd = trigger.isNotBlank() && expansion.isNotBlank()
        Row(
            Modifier.clip(CircleShape).background(if (canAdd) GlassFillActive else Color.Transparent).border(1.dp, if (canAdd) IvyOrange else GlassBorder, CircleShape)
                .clickable(enabled = canAdd) {
                    dictionary.saveSnippet(null, trigger, expansion); added += trigger.trim() to expansion.trim(); trigger = ""; expansion = ""
                }.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, null, tint = if (canAdd) IvyOrange else TextMuted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add shortcut", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (canAdd) TextPrimary else TextMuted)
        }
        Spacer(Modifier.height(16.dp))
        added.forEach { (t, e) ->
            Rise {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(GlassFill)
                    .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("\"$t\"", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = IvyOrange)
                    Text("  →  ", fontFamily = InterFamily, fontSize = 14.sp, color = TextMuted)
                    Text(e, fontFamily = InterFamily, fontSize = 14.sp, color = TextPrimary, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, fontFamily = InterFamily, fontSize = 14.sp, color = TextMuted) },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = IvyOrange, unfocusedBorderColor = GlassBorder,
            focusedContainerColor = GlassFill, unfocusedContainerColor = GlassFill,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = IvyOrange
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun InputWithAdd(value: String, onChange: (String) -> Unit, placeholder: String, onAdd: () -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(placeholder, fontFamily = InterFamily, fontSize = 14.sp, color = TextMuted) },
        trailingIcon = {
            Box(Modifier.padding(end = 6.dp).size(36.dp).clip(CircleShape).background(if (value.isNotBlank()) IvyOrange else GlassFillActive)
                .clickable(enabled = value.isNotBlank(), onClick = onAdd), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Add", tint = if (value.isNotBlank()) Color.White else TextMuted, modifier = Modifier.size(20.dp))
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAdd() }),
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = IvyOrange, unfocusedBorderColor = GlassBorder,
            focusedContainerColor = GlassFill, unfocusedContainerColor = GlassFill,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = IvyOrange
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** Try it for real (simulated in this UI build): tap the box, the bubble appears, tap it, words appear. */
@Composable
private fun Practice(phase: PracticePhase, text: String, onTapBox: () -> Unit, onTapBubble: () -> Unit) {
    val title = when (phase) {
        PracticePhase.IDLE -> "Try it once"
        PracticePhase.FOCUSED -> "Hold the bubble"
        PracticePhase.LISTENING -> "Listening…"
        PracticePhase.WRITING -> "Writing…"
        PracticePhase.DONE -> "You're all set"
    }
    val sub = when (phase) {
        PracticePhase.IDLE -> "Tap the box below, like you would in any app."
        PracticePhase.FOCUSED -> "Press and hold it for half a second, then say anything. (A quick tap opens more options.)"
        PracticePhase.LISTENING -> "Say a sentence, Ivy finishes by itself."
        PracticePhase.WRITING -> "Ivy is typing it for you."
        PracticePhase.DONE -> "That's exactly how Ivy works in every app."
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        AnimatedContent(targetState = title, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "pt") { t ->
            Text(t, fontFamily = InterFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = (-0.6).sp, color = TextPrimary, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Text(sub, fontFamily = InterFamily, fontSize = 15.sp, color = TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = phase == PracticePhase.FOCUSED || phase == PracticePhase.LISTENING,
                enter = scaleIn(tween(260)) + fadeIn(), exit = scaleOut(tween(200)) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = 10.dp)
            ) {
                var menu by remember { mutableStateOf(false) }
                LaunchedEffect(phase) { if (phase != PracticePhase.FOCUSED) menu = false }
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.animation.AnimatedVisibility(visible = menu, enter = fadeIn(tween(150)), exit = fadeOut(tween(120))) {
                        DemoMenu(
                            cancelEnabled = phase == PracticePhase.LISTENING,
                            onHistory = { menu = false },
                            onRecord = { menu = false; onTapBubble() },
                            onCancel = { menu = false }
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    var held by remember { mutableStateOf(false) }
                    val ring by androidx.compose.animation.core.animateFloatAsState(
                        if (held) 1f else 0f,
                        if (held) tween(com.example.ui.bubble.HOLD_TO_RECORD_MS.toInt(), easing = androidx.compose.animation.core.LinearEasing) else tween(0), label = "practice-ring"
                    )
                    Box(Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            held = true
                            val t0 = System.currentTimeMillis()
                            val up = withTimeoutOrNull(com.example.ui.bubble.HOLD_TO_RECORD_MS) { waitForUpOrCancellation() }
                            held = false
                            if (up != null) menu = !menu
                            else if (System.currentTimeMillis() - t0 >= com.example.ui.bubble.HOLD_TO_RECORD_MS) { menu = false; onTapBubble(); waitForUpOrCancellation() }
                        }
                    }) { DemoBubble(recording = phase == PracticePhase.LISTENING, ring = ring) }
                }
            }
        }
        val focused = phase != PracticePhase.IDLE
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(GlassFill)
                .border(1.5.dp, if (focused) IvyOrange else GlassBorder, RoundedCornerShape(20.dp))
                .clickable(onClick = onTapBox).padding(18.dp)
        ) {
            Text(if (text.isEmpty()) "Tap here and say something" else text, fontFamily = InterFamily, fontSize = 16.sp, lineHeight = 23.sp,
                color = if (text.isEmpty()) TextMuted else TextPrimary)
        }
    }
}
