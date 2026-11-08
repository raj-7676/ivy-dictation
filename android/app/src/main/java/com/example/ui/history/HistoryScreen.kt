package com.example.ui.history

import com.example.ui.components.PageHeader
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.IvyOrangeSoft
import com.example.ui.theme.InterFamily
import com.example.ui.theme.SyneFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Auto dismiss toast after 2s
    // Messages as Android toasts, so they show above the detail sheet too
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    val filteredItems = uiState.items.filter {
        if (uiState.searchQuery.isBlank()) true
        else it.fullText.contains(uiState.searchQuery, ignoreCase = true) ||
                it.appName.contains(uiState.searchQuery, ignoreCase = true)
    }

    val todayItems = filteredItems.filter { it.group == HistoryGroup.TODAY }
    val yesterdayItems = filteredItems.filter { it.group == HistoryGroup.YESTERDAY }
    val earlierItems = filteredItems.filter { it.group == HistoryGroup.EARLIER }

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
            // Header
            item {
                PageHeader(
                    eyebrow = "Your dictations",
                    title = "History",
                    subtitle = "Everything you've said, kept only on this phone."
                ) {
                    if (uiState.items.isNotEmpty()) {
                        Text(
                            text = "Clear all",
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = IvyOrange,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { viewModel.showClearConfirm(true) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("clear_all_button")
                        )
                    }
                }
            }

            // Search Bar
            item {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.onSearchQueryChanged(it) },
                    placeholder = {
                        Text(
                            text = "Search transcripts",
                            fontFamily = InterFamily,
                            fontSize = 14.sp,
                            color = TextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.onSearchQueryChanged("") }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = IvyOrange,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = GlassFill,
                        unfocusedContainerColor = GlassFill,
                        cursorColor = IvyOrange,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_search_bar")
                )
            }

            // "Keep for" Retention Selector
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Keep transcripts for",
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(RetentionPeriod.values()) { period ->
                            val isSelected = period == uiState.retentionPeriod
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) IvyOrange else GlassFill)
                                    .border(
                                        1.dp,
                                        if (isSelected) IvyOrange else GlassBorder,
                                        RoundedCornerShape(14.dp)
                                    )
                                    .clickable { viewModel.setRetentionPeriod(period) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = period.label,
                                    fontFamily = InterFamily,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.White else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Empty State
            if (filteredItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(GlassFill)
                                    .border(1.dp, GlassBorder, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                            Text(
                                text = if (uiState.searchQuery.isBlank()) "No dictations yet" else "Nothing matches \"${uiState.searchQuery}\"",
                                fontFamily = InterFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = if (uiState.searchQuery.isBlank()) "Your offline transcripts will appear here automatically." else "Try another word.",
                                fontFamily = InterFamily,
                                fontSize = 13.sp,
                                color = TextMuted
                            )
                        }
                    }
                }
            } else {
                // Group: Today
                if (todayItems.isNotEmpty()) {
                    item {
                        HistoryGroupHeader(title = "Today")
                    }
                    items(todayItems) { item ->
                        HistoryCard(item = item, onClick = { viewModel.onItemSelected(item) })
                    }
                }

                // Group: Yesterday
                if (yesterdayItems.isNotEmpty()) {
                    item {
                        HistoryGroupHeader(title = "Yesterday")
                    }
                    items(yesterdayItems) { item ->
                        HistoryCard(item = item, onClick = { viewModel.onItemSelected(item) })
                    }
                }

                // Group: Earlier
                if (earlierItems.isNotEmpty()) {
                    item {
                        HistoryGroupHeader(title = "Earlier")
                    }
                    items(earlierItems) { item ->
                        HistoryCard(item = item, onClick = { viewModel.onItemSelected(item) })
                    }
                }
            }
        }

    }

    // Detail Bottom Sheet
    if (uiState.selectedItemForDetail != null) {
        val selected = uiState.selectedItemForDetail!!
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissDetail() },
            sheetState = bottomSheetState,
            containerColor = IvyNearBlack,
            tonalElevation = 8.dp,
            dragHandle = null
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Drag handle pill
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(GlassBorder)
                        .align(Alignment.CenterHorizontally)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassFill)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = selected.appName,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = IvyOrange
                        )
                    }

                    Text(
                        text = "${selected.timeFormatted} · ${selected.durationText}",
                        fontFamily = InterFamily,
                        fontSize = 12.sp,
                        color = TextMuted
                    )
                }

                // Full Text Glass Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassCard(shape = RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = selected.fullText,
                        fontFamily = InterFamily,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = TextPrimary
                    )
                }

                // Actions: two even rows of three (no squeezing, no wrapped labels)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                    DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.ContentCopy,
                        label = "Copy",
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Ivy Transcription", selected.fullText))
                            viewModel.showToast("Copied to clipboard")
                        }
                    )
                    DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Share,
                        label = "Share",
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, selected.fullText)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Transcription"))
                        }
                    )
                    if (selected.audio != null) DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = if (uiState.playingId == selected.id) Icons.Default.Stop else Icons.Default.PlayArrow,
                        label = if (uiState.playingId == selected.id) "Stop" else "Play",
                        onClick = { viewModel.togglePlay(selected) }
                    )
                        if (selected.audio == null) Spacer(Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                    if (selected.audio != null) DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Refresh,
                        label = if (uiState.retryingId == selected.id) "Writing…" else "Retry",
                        onClick = { viewModel.retry(selected) }
                    )
                    DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = if (selected.id in uiState.proDone) Icons.Default.Undo else Icons.Default.AutoFixHigh,
                        label = when {
                            uiState.proId == selected.id -> "Writing…"
                            selected.id in uiState.proDone -> "Undo"
                            else -> "Professional"
                        },
                        onClick = { viewModel.makeProfessional(selected) }
                    )
                    DetailActionItem(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.DeleteOutline,
                        label = "Delete",
                        tint = Color(0xFFFF4D4D),
                        onClick = { viewModel.deleteItem(selected.id) }
                    )
                        if (selected.audio == null) Spacer(Modifier.weight(1f))
                    }
                }

                if (selected.audio != null) {
                    Text(
                        text = "Your recording is saved in ${com.example.data.AudioFiles.whereText}",
                        fontFamily = InterFamily,
                        fontSize = 11.sp,
                        color = TextMuted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Clear All Confirmation Dialog
    if (uiState.showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.showClearConfirm(false) },
            containerColor = IvyNearBlack,
            title = {
                Text(
                    text = "Clear all dictations?",
                    fontFamily = InterFamily,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    text = "This will permanently remove all offline transcription history on this device.",
                    fontFamily = InterFamily,
                    fontSize = 14.sp,
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.clearAll() },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF4D4D))
                ) {
                    Text("Clear all", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.showClearConfirm(false) },
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun HistoryGroupHeader(title: String) {
    Text(
        text = title,
        fontFamily = InterFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        color = TextSecondary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun HistoryCard(
    item: HistoryItem,
    onClick: () -> Unit
) {
    IvyGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${item.id}"),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = item.previewText,
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = TextPrimary,
                maxLines = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = item.appName,
                        fontFamily = InterFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = IvyOrangeSoft
                    )
                }

                Text(
                    text = "${item.durationText} · ${item.timeFormatted}",
                    fontFamily = InterFamily,
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun DetailActionItem(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = TextPrimary,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(GlassFill)
                .border(1.dp, GlassBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontFamily = InterFamily,
            fontSize = 12.sp,
            maxLines = 1,
            color = tint
        )
    }
}
