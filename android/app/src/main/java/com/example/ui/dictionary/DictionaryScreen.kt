package com.example.ui.dictionary

import com.example.ui.components.PageHeader
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.IvyGlassCard
import com.example.ui.components.glassCard
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.IvyNearBlack
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.InterFamily
import com.example.ui.theme.SyneFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DictionaryScreen(
    viewModel: DictionaryViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.statusFeedback) {
        if (uiState.statusFeedback != null) {
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Title
            item {
                PageHeader(
                    eyebrow = "Words Ivy knows",
                    title = "Dictionary",
                    subtitle = "Names and terms Ivy should always get right."
                )
            }

            // Segmented Control (Dictionary | Snippets)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassFill)
                        .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SegmentButton(
                        label = "Dictionary",
                        isSelected = uiState.activeSegment == DictionarySegment.DICTIONARY,
                        onClick = { viewModel.selectSegment(DictionarySegment.DICTIONARY) },
                        modifier = Modifier.weight(1f)
                    )
                    SegmentButton(
                        label = "Snippets",
                        isSelected = uiState.activeSegment == DictionarySegment.SNIPPETS,
                        onClick = { viewModel.selectSegment(DictionarySegment.SNIPPETS) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Segment Content
            item {
                AnimatedContent(
                    targetState = uiState.activeSegment,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "SegmentContent"
                ) { segment ->
                    when (segment) {
                        DictionarySegment.DICTIONARY -> {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Add personal word input
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = uiState.newWordInput,
                                        onValueChange = { viewModel.onNewWordInputChanged(it) },
                                        placeholder = {
                                            Text(
                                                text = "Add a word or phrase",
                                                fontFamily = InterFamily,
                                                fontSize = 14.sp,
                                                color = TextMuted
                                            )
                                        },
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = IvyOrange,
                                            unfocusedBorderColor = GlassBorder,
                                            focusedContainerColor = GlassFill,
                                            unfocusedContainerColor = GlassFill,
                                            cursorColor = IvyOrange,
                                            focusedTextColor = TextPrimary,
                                            unfocusedTextColor = TextPrimary
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("new_word_input")
                                    )

                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(IvyOrange)
                                            .clickable { viewModel.addPersonalWord() }
                                            .testTag("add_word_button"),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "Add Word",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // Personal words chip cloud
                                Text(
                                    text = "Your custom words (${uiState.personalWords.size})",
                                    fontFamily = InterFamily,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    uiState.personalWords.forEach { word ->
                                        WordChip(
                                            word = word,
                                            onRemove = { viewModel.removePersonalWord(word) }
                                        )
                                    }
                                }

                                // Explanatory Info Card
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .glassCard(shape = RoundedCornerShape(16.dp))
                                        .padding(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = null,
                                            tint = IvyOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(
                                            text = "Ivy biases the on-device speech model to accurately recognize your unique vocabulary, product names, acronyms, and contacts.",
                                            fontFamily = InterFamily,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }

                        DictionarySegment.SNIPPETS -> {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Text expansions",
                                        fontFamily = InterFamily,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = TextSecondary
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(IvyOrange)
                                            .clickable { viewModel.showAddSnippet(true) }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                            .testTag("add_snippet_button")
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Add snippet",
                                                fontFamily = InterFamily,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }

                                uiState.snippets.forEach { snippet ->
                                    SnippetCard(
                                        snippet = snippet,
                                        onEdit = { viewModel.startEditSnippet(snippet) },
                                        onDelete = { viewModel.deleteSnippet(snippet.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Feedback Pill
        if (uiState.statusFeedback != null) {
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
                    text = uiState.statusFeedback ?: "",
                    fontFamily = InterFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
            }
        }
    }

    // Add/Edit Snippet Dialog
    if (uiState.showAddSnippetDialog || uiState.editingSnippet != null) {
        val isEditing = uiState.editingSnippet != null
        var trigger by remember(uiState.editingSnippet) {
            mutableStateOf(uiState.editingSnippet?.triggerPhrase ?: "")
        }
        var expansion by remember(uiState.editingSnippet) {
            mutableStateOf(uiState.editingSnippet?.expansionText ?: "")
        }

        AlertDialog(
            onDismissRequest = {
                if (isEditing) viewModel.cancelEditSnippet()
                else viewModel.showAddSnippet(false)
            },
            containerColor = IvyNearBlack,
            title = {
                Text(
                    text = if (isEditing) "Edit Snippet" else "Add New Snippet",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Speak the trigger phrase and Ivy will paste the full expansion.",
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                    OutlinedTextField(
                        value = trigger,
                        onValueChange = { trigger = it },
                        label = { Text("Trigger phrase (e.g. 'my email')") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IvyOrange,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = expansion,
                        onValueChange = { expansion = it },
                        label = { Text("Expansion (e.g. 'alex@glow.co')") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = IvyOrange,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSnippet(
                            id = uiState.editingSnippet?.id,
                            trigger = trigger,
                            expansion = expansion
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = IvyOrange)
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (isEditing) viewModel.cancelEditSnippet()
                        else viewModel.showAddSnippet(false)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SegmentButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) IvyOrange else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = InterFamily,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp,
            color = if (isSelected) Color.White else TextSecondary
        )
    }
}

@Composable
private fun WordChip(
    word: String,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassFill)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = word,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 13.sp,
            color = TextPrimary
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove $word",
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SnippetCard(
    snippet: SnippetItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    IvyGlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "\"${snippet.triggerPhrase}\"",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = IvyOrange
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = snippet.expansionText,
                    fontFamily = InterFamily,
                    fontSize = 13.sp,
                    color = TextPrimary,
                    maxLines = 1
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFFFF4D4D),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
