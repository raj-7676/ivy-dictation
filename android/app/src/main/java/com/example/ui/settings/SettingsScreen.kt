package com.example.ui.settings

import androidx.compose.runtime.DisposableEffect
import com.example.service.Permissions
import androidx.compose.ui.platform.LocalContext
import com.example.ui.theme.GlassFillActive
import com.example.ui.theme.Appearance
import com.example.ui.components.PageHeader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.bubble.BubbleDockSide
import com.example.ui.bubble.BubbleSize
import com.example.ui.components.IvyBrandFooter
import com.example.ui.components.IvyGlassCard
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyGreen
import com.example.ui.theme.IvyNearBlack
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.IvyOrangeSoft
import com.example.ui.theme.InterFamily
import com.example.ui.theme.SyneFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

private data class PermState(val mic: Boolean, val overlay: Boolean, val accessibility: Boolean) {
    companion object {
        fun read(ctx: android.content.Context) =
            PermState(Permissions.mic(ctx), Permissions.overlay(ctx), Permissions.accessibility(ctx))
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onReplayOnboarding: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var perms by remember { mutableStateOf(PermState.read(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                perms = PermState.read(context)
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(uiState.feedbackMessage) {
        if (uiState.feedbackMessage != null) {
            delay(2000)
            viewModel.clearFeedback()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Title
            item {
                PageHeader(
                    eyebrow = "Preferences",
                    title = "Settings"
                )
            }

            // Appearance: Glass opacity, as on desktop Ivy. Below 100% the phone's wallpaper shows through.
            item { SectionHeader(title = "Appearance") }
            item {
                val context = LocalContext.current
                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        AppearanceSlider(
                            label = "Glass opacity",
                            value = Appearance.glassOpacity,
                            onChange = { Appearance.glassOpacity = it },
                            onDone = { Appearance.save(context) }
                        )
                    }
                }
            }

            // 2. Microphone & Audio Meter
            item {
                SectionHeader(title = "Microphone & Sensitivity")
            }
            item {
                var micMenuExpanded by remember { mutableStateOf(false) }

                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Microphone: Android picks the phone's mic, or a wired headset's when one is plugged in.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(38.dp).clip(CircleShape).background(IvyOrange.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) { Icon(imageVector = Icons.Default.Mic, contentDescription = null, tint = IvyOrange, modifier = Modifier.size(19.dp)) }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "Microphone", fontFamily = InterFamily, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                Text(text = "Phone mic (automatic)", fontFamily = InterFamily, fontSize = 12.sp, color = TextMuted,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Text(
                                text = if (uiState.isMicTesting) "Testing" else "Test",
                                fontFamily = InterFamily, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                                color = if (uiState.isMicTesting) TextMuted else Color(0xFF1B0D04),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .width(78.dp)
                                    .clip(CircleShape)
                                    .background(if (uiState.isMicTesting) GlassFill else IvyOrange)
                                    .clickable(enabled = !uiState.isMicTesting) { viewModel.testMic() }
                                    .padding(vertical = 10.dp)
                            )
                        }

                        // Test Meter
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Mic level",
                                    fontFamily = InterFamily,
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = if (uiState.isMicTesting) "Listening… speak now" else "Tap Test and speak",
                                    fontFamily = InterFamily,
                                    fontSize = 11.sp,
                                    color = if (uiState.isMicTesting) IvyGreen else TextMuted
                                )
                            }

                            AudioMeterCanvas(
                                level = uiState.micMeterLevel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(18.dp)
                            )
                        }
                    }
                }
            }

            // 3. Floating Bubble Ergonomics
            item {
                SectionHeader(title = "Floating Bubble Ergonomics")
            }
            item {
                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Size Slider
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bubble size",
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = uiState.bubbleSize.name.lowercase().replaceFirstChar { it.uppercase() },
                                    fontFamily = InterFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = IvyOrange
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BubbleSize.values().forEach { size ->
                                    val isChosen = size == uiState.bubbleSize
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isChosen) IvyOrange else GlassFill)
                                            .clickable { viewModel.setBubbleSize(size) }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = size.name.lowercase().replaceFirstChar { it.uppercase() },
                                            fontFamily = InterFamily,
                                            fontSize = 12.sp,
                                            fontWeight = if (isChosen) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isChosen) Color.White else TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        // Opacity Slider
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Bubble opacity",
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${(uiState.bubbleOpacity * 100).toInt()}%",
                                    fontFamily = InterFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = IvyOrange
                                )
                            }
                            Slider(
                                value = uiState.bubbleOpacity,
                                onValueChange = { viewModel.setBubbleOpacity(it) },
                                valueRange = 0.40f..1.0f,
                                colors = SliderDefaults.colors(
                                    thumbColor = IvyOrange,
                                    activeTrackColor = IvyOrange,
                                    inactiveTrackColor = GlassFill
                                )
                            )
                        }

                        // Dock Side Selection
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Dock side",
                                fontFamily = InterFamily,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (uiState.bubbleDockSide == BubbleDockSide.LEFT) IvyOrange else GlassFill)
                                        .clickable { viewModel.setBubbleSide(BubbleDockSide.LEFT) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Left",
                                        fontFamily = InterFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (uiState.bubbleDockSide == BubbleDockSide.LEFT) Color.White else TextSecondary
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (uiState.bubbleDockSide == BubbleDockSide.RIGHT) IvyOrange else GlassFill)
                                        .clickable { viewModel.setBubbleSide(BubbleDockSide.RIGHT) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Right",
                                        fontFamily = InterFamily,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (uiState.bubbleDockSide == BubbleDockSide.RIGHT) Color.White else TextSecondary
                                    )
                                }
                            }
                        }

                        // Show only when keyboard is open
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Show only when keyboard is open",
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "Hides bubble when not in a text field",
                                    fontFamily = InterFamily,
                                    fontSize = 11.sp,
                                    color = TextMuted
                                )
                            }
                            Switch(
                                checked = uiState.showOnlyWhenKeyboardIsOpen,
                                onCheckedChange = { viewModel.toggleKeyboardOnly(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = IvyOrange,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = GlassFill
                                )
                            )
                        }
                    }
                }
            }

            // 4. Feedback Toggles (Haptics, Sounds)
            item {
                SectionHeader(title = "Tactile & Audio Feedback")
            }
            item {
                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Vibration,
                                    contentDescription = null,
                                    tint = IvyOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Haptic feedback on tap / hold",
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                            }
                            Switch(
                                checked = uiState.isHapticsEnabled,
                                onCheckedChange = { viewModel.toggleHaptics(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = IvyOrange,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = GlassFill
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Start & finish click sounds",
                                    fontFamily = InterFamily,
                                    fontSize = 13.sp,
                                    color = TextPrimary
                                )
                            }
                            Switch(
                                checked = uiState.isSoundsEnabled,
                                onCheckedChange = { viewModel.toggleSounds(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = IvyOrange,
                                    uncheckedThumbColor = TextMuted,
                                    uncheckedTrackColor = GlassFill
                                )
                            )
                        }
                    }
                }
            }

            // 5. System Permissions Status (read from Android; tapping a row opens the screen that changes it)
            item {
                SectionHeader(title = "Required Android Permissions")
            }
            item {
                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        PermissionRow(
                            title = "Microphone",
                            subtitle = "Capture audio locally on your device",
                            isGranted = perms.mic,
                            onToggle = { context.startActivity(Permissions.appInfoIntent(context)) }
                        )
                        PermissionRow(
                            title = "Display over other apps",
                            subtitle = "Floating bubble docked over any app",
                            isGranted = perms.overlay,
                            onToggle = { context.startActivity(Permissions.overlayIntent(context)) }
                        )
                        PermissionRow(
                            title = "Accessibility service",
                            subtitle = "Type directly into focused input fields",
                            isGranted = perms.accessibility,
                            onToggle = { context.startActivity(Permissions.accessibilityIntent()) }
                        )
                    }
                }
            }

            // 6. Onboarding & Tour
            item {
                IvyGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onReplayOnboarding
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Replay onboarding walkthrough",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Review setup, permissions, and model specs",
                                fontFamily = InterFamily,
                                fontSize = 12.sp,
                                color = TextMuted
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = IvyOrange
                        )
                    }
                }
            }

            // 7. About & Support Ivy (Buy Me a Coffee)
            item {
                SectionHeader(title = "About Ivy")
            }
            item {
                IvyGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Version",
                                fontFamily = InterFamily,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = remember {
                                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                                        .getOrNull() ?: "?"
                                },
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "License",
                                fontFamily = InterFamily,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "MIT + Commons Clause",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Author",
                                fontFamily = InterFamily,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Text(
                                text = "Glow.co",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = IvyOrange
                            )
                        }

                        // Buy Me a Coffee Button (No ads anywhere)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFFDD00))
                                .clickable {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse("https://buymeacoffee.com/glow.co"))
                                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Coffee,
                                    contentDescription = "Buy me a coffee",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Support Ivy (Buy Me a Coffee)",
                                    fontFamily = InterFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = Color.Black
                                )
                            }
                        }
                    }
                }
            }

            // Footer
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IvyBrandFooter()
                }
            }
        }

        // Feedback Toast
        if (uiState.feedbackMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(IvyNearBlack)
                    .border(1.dp, IvyOrange, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = uiState.feedbackMessage ?: "",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = TextSecondary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = TextPrimary
            )
            Text(
                text = subtitle,
                fontFamily = InterFamily,
                fontSize = 11.sp,
                color = TextMuted
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (isGranted) IvyGreen.copy(alpha = 0.15f) else IvyOrange.copy(alpha = 0.15f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                text = if (isGranted) "Granted ✓" else "Fix",
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = if (isGranted) IvyGreen else IvyOrange
            )
        }
    }
}

@Composable
private fun AudioMeterCanvas(
    level: Float,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val totalBars = 24
        val barWidth = 6.dp.toPx()
        val spacing = (size.width - (totalBars * barWidth)) / (totalBars - 1).coerceAtLeast(1)
        val activeBars = (level.coerceIn(0f, 1f) * totalBars).toInt()

        for (i in 0 until totalBars) {
            val isActive = i < activeBars
            val color = when {
                !isActive -> GlassFill
                i > (totalBars * 0.75f) -> Color(0xFFFF4D4D)
                i > (totalBars * 0.5f) -> IvyOrange
                else -> IvyGreen
            }

            val x = i * (barWidth + spacing)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = CornerRadius(2.dp.toPx())
            )
        }
    }
}

@Composable
private fun AppearanceSlider(label: String, value: Float, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, fontFamily = InterFamily, fontSize = 13.sp, color = TextPrimary)
            Text(
                text = "${(value * 100).toInt()}%",
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = IvyOrange
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = IvyOrange,
                activeTrackColor = IvyOrange,
                inactiveTrackColor = GlassFillActive
            )
        )
    }
}
