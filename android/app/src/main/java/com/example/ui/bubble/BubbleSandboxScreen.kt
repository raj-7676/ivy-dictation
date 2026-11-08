package com.example.ui.bubble

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardBackspace
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.IvyLogoMark
import com.example.ui.components.IvyWordmark
import com.example.ui.components.glassCard
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyGreen
import com.example.ui.theme.IvyNearBlack
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.IvyOrangeSoft
import com.example.ui.theme.InterFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

data class ChatMessage(
    val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timeFormatted: String
)

/**
 * Host screen demonstrating the standalone BubbleOverlay over a mock chat application
 * with an active open mobile keyboard layout.
 */
@Composable
fun BubbleSandboxScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bubbleState = rememberBubbleState(context = context)

    // Current text in the mock input box
    var inputText by remember { mutableStateOf("") }
    // Like the real app: the keyboard and the Ivy bubble appear when a text field is tapped (focused).
    var inputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val messages = remember {
        mutableStateListOf(
            ChatMessage("m1", "Hey! Did you prepare the product update for the team?", false, "10:14 AM"),
            ChatMessage("m2", "Yes, just doing a quick final review on mobile.", true, "10:15 AM"),
            ChatMessage("m3", "Great! Let me know when you send the email tommorow.", false, "10:16 AM")
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0C15))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Mock Messaging Header
            MockChatHeader(onBack = onBack)

            // Top Simulator Controls Bar (to test specific fallback states easily)
            SimulatorControlsBar(
                bubbleState = bubbleState,
                onForceState = { mode -> bubbleState.triggerFallbackState(mode) }
            )

            // Chat Message Thread
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(indication = null, interactionSource = null) { focusManager.clearFocus() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Today, 10:14 AM",
                            fontFamily = InterFamily,
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }

                items(messages.size) { index ->
                    val msg = messages[index]
                    ChatBubble(message = msg)
                }
            }

            // Input Field Bar (Simulated focused input field)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(IvyNearBlack)
                    .border(1.dp, GlassBorder, RoundedCornerShape(0.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    // The mock keyboard below types here; readOnly keeps the phone's own keyboard from covering it.
                    readOnly = true,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            text = "Tap the bubble for options, or hold it half a second to dictate…",
                            fontFamily = InterFamily,
                            fontSize = 13.sp,
                            color = TextMuted
                        )
                    },
                    trailingIcon = {
                        if (inputText.isNotEmpty()) {
                            IconButton(onClick = { inputText = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IvyOrange,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = GlassFill,
                        unfocusedContainerColor = GlassFill,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = IvyOrange
                    ),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { inputFocused = it.isFocused }
                        .testTag("mock_chat_input")
                )

                // Send Button
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (inputText.isNotBlank()) IvyOrange else GlassFill)
                        .clickable(enabled = inputText.isNotBlank()) {
                            messages.add(
                                ChatMessage(
                                    id = "m_${System.currentTimeMillis()}",
                                    text = inputText,
                                    isFromMe = true,
                                    timeFormatted = "Just now"
                                )
                            )
                            inputText = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) Color.White else TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Simulated keyboard: slides up when the text field is tapped, like a real one.
            AnimatedVisibility(
                visible = inputFocused,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
            MockKeyboardLayout(onKeyClick = { key ->
                if (key == "DEL") {
                    if (inputText.isNotEmpty()) inputText = inputText.dropLast(1)
                } else if (key == "SPACE") {
                    inputText += " "
                } else {
                    inputText += key
                }
            })
            }
        }

        // STANDALONE BubbleOverlay: floating docked just above the keyboard
        // Decoupled from screen navigation so it can later move into a real overlay Service!
        // Shown while a text field is focused (or while a dictation is still running).
        AnimatedVisibility(
            visible = inputFocused || bubbleState.mode != BubbleMode.IDLE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
        BubbleOverlay(
            state = bubbleState,
            currentHostText = inputText,
            onTextEmitted = { dictatedText ->
                inputText = if (inputText.isBlank()) dictatedText else "$inputText $dictatedText"
            },
            onTextCorrected = { correctedText ->
                inputText = correctedText
            },
            modifier = Modifier.padding(bottom = 300.dp) // above the input bar and the open keyboard
        )
        }
    }
}

@Composable
private fun MockChatHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(IvyNearBlack)
            .border(1.dp, GlassBorder, RoundedCornerShape(0.dp))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("sandbox_back_button")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Ivy",
                    tint = TextPrimary
                )
            }

            // Contact Avatar with online indicator
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF332A42)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AR",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
            }

            Column {
                Text(
                    text = "Alex Rivera",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(IvyGreen)
                    )
                    Text(
                        text = "online",
                        fontFamily = InterFamily,
                        fontSize = 11.sp,
                        color = IvyGreen
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = {}) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SimulatorControlsBar(
    bubbleState: BubbleState,
    onForceState: (BubbleMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF140F1D))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Test states:",
            fontFamily = InterFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextMuted
        )

        TestStateChip(
            label = "Reset Idle",
            onClick = { bubbleState.resetToIdle() }
        )
        TestStateChip(
            label = "Copied",
            onClick = { onForceState(BubbleMode.FALLBACK_COPIED) }
        )
        TestStateChip(
            label = "No Speech",
            onClick = { onForceState(BubbleMode.FALLBACK_NO_SPEECH) }
        )
        TestStateChip(
            label = "Retry Error",
            onClick = { onForceState(BubbleMode.FALLBACK_ERROR) }
        )
    }
}

@Composable
private fun TestStateChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontFamily = InterFamily,
            fontSize = 10.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (message.isFromMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (message.isFromMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (message.isFromMe) 16.dp else 4.dp,
                            bottomEnd = if (message.isFromMe) 4.dp else 16.dp
                        )
                    )
                    .background(
                        if (message.isFromMe) IvyOrange.copy(alpha = 0.9f)
                        else Color(0xFF211B2B)
                    )
                    .border(
                        1.dp,
                        if (message.isFromMe) IvyOrange else GlassBorder,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message.text,
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    color = Color.White
                )
            }
            Text(
                text = message.timeFormatted,
                fontFamily = InterFamily,
                fontSize = 10.sp,
                color = TextMuted,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Simulated mobile keyboard rendering QWERTY rows to provide an authentic in-app typing context.
 */
@Composable
private fun MockKeyboardLayout(onKeyClick: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(230.dp)
            .background(Color(0xFF16121F))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        // Suggestions Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SuggestionChip(text = "I'll", onClick = { onKeyClick("I'll ") })
            SuggestionChip(text = "The", onClick = { onKeyClick("The ") })
            SuggestionChip(text = "Thanks!", onClick = { onKeyClick("Thanks! ") })
        }

        // Row 1: Q W E R T Y U I O P
        KeyboardRow(keys = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"), onKeyClick = onKeyClick)

        // Row 2: A S D F G H J K L
        KeyboardRow(keys = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"), onKeyClick = onKeyClick)

        // Row 3: Shift, Z X C V B N M, Backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyCap(text = "⇧", weight = 1.3f, onClick = {})
            listOf("Z", "X", "C", "V", "B", "N", "M").forEach { k ->
                KeyCap(text = k, weight = 1f, onClick = { onKeyClick(k.lowercase()) })
            }
            KeyCap(text = "⌫", weight = 1.3f, onClick = { onKeyClick("DEL") })
        }

        // Row 4: 123, Emoji, Spacebar, Return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyCap(text = "?123", weight = 1.5f, onClick = {})
            KeyCap(text = "Space", weight = 4.5f, onClick = { onKeyClick("SPACE") })
            KeyCap(text = "↵", weight = 1.5f, isAccent = true, onClick = { onKeyClick("\n") })
        }
    }
}

@Composable
private fun KeyboardRow(keys: List<String>, onKeyClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEach { key ->
            KeyCap(
                text = key,
                weight = 1f,
                onClick = { onKeyClick(key.lowercase()) }
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.KeyCap(
    text: String,
    weight: Float,
    isAccent: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(weight)
            .height(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (isAccent) IvyOrange else Color(0xFF2C2438))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = Color.White
        )
    }
}

@Composable
private fun SuggestionChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            fontFamily = InterFamily,
            fontSize = 12.sp,
            color = TextSecondary
        )
    }
}
