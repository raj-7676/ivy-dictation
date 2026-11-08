package com.example.ui.home

import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassFill
import com.example.ui.theme.GlassFillActive
import com.example.ui.components.PageHeader
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocalFireDepartment
import com.example.ui.theme.InterFamily
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.NumberFormat

/**
 * Home (layout 1, picked by Yash 2026-11-03): the shared page header (orange date eyebrow, greeting, subline), one
 * stats card, then recent dictations. Same surfaces, header and spacing as History / Dictionary / Settings.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    @Suppress("UNUSED_PARAMETER") onTryItClicked: () -> Unit, // kept for the caller; Home has no "try it" button any more
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val date = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM"))

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { PageHeader(eyebrow = date, title = uiState.greeting, subtitle = uiState.subline, modifier = Modifier.padding(bottom = 10.dp)) }
        item { HeroCard(uiState.wordsDictated, uiState.wordsPerMinute, uiState.dayStreak) }
        item { RecentHeader(onNavigateToHistory) }
        if (uiState.recentDictations.isEmpty()) item {
            Text(
                "Nothing yet. Tap any text box on your phone, then the Ivy bubble, and talk.",
                fontFamily = InterFamily, fontSize = 13.sp, color = TextMuted,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }
        items(uiState.recentDictations, key = { it.id }) { item ->
            RecentCard(item, onNavigateToHistory) {
                clipboard.setText(AnnotatedString(item.previewText))
                android.widget.Toast.makeText(context, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun RecentHeader(onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("RECENT", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.6.sp, color = TextMuted)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onSeeAll).padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text("See all", fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = IvyOrange)
            Spacer(Modifier.width(3.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = IvyOrange, modifier = Modifier.size(14.dp).rotate(-45f))
        }
    }
}

@Composable
private fun HeroCard(words: Int, wpm: Int, streak: Int) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, shape)
            .padding(horizontal = 24.dp, vertical = 26.dp)
            .testTag("hero_card")
    ) {
        Text(
            text = NumberFormat.getIntegerInstance().format(words),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 60.sp,
            lineHeight = 64.sp,
            letterSpacing = (-2).sp,
            color = TextPrimary
        )
        Text(text = "words dictated", fontFamily = InterFamily, fontSize = 17.sp, color = TextSecondary)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("$wpm wpm", Icons.Rounded.Bolt)
            StatChip("$streak day streak", Icons.Rounded.LocalFireDepartment)
        }
    }
}

@Composable
private fun StatChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(GlassFillActive)
            .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = IvyOrange, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
    }
}

@Composable
private fun RecentCard(item: DictationPreview, onOpen: () -> Unit, onCopy: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassBorder, shape)
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 16.dp)
            .testTag("recent_dictation_${item.id}")
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppBadge(item.targetApp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "${item.targetApp} • ${item.timeAgo}",
                fontFamily = InterFamily,
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onCopy),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = TextMuted, modifier = Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.previewText,
            fontFamily = InterFamily,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

/** A small rounded tile in the app's own colour with its initial (real app icons come from the system later). */
@Composable
private fun AppBadge(app: String) {
    val color = when (app.lowercase()) {
        "slack" -> Color(0xFF4A154B)
        "whatsapp" -> Color(0xFF25D366)
        "gmail" -> Color(0xFFEA4335)
        "telegram" -> Color(0xFF229ED9)
        "notes" -> Color(0xFFF5B400)
        else -> IvyOrange
    }
    Box(
        modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = app.take(1).uppercase(),
            fontFamily = InterFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier.offset(y = (-0.5).dp)
        )
    }
}
