package com.example.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.InterFamily
import com.example.ui.theme.IvyOrange
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * The one header every tab uses, so Home, History, Dictionary and Settings read as one app: a small orange
 * eyebrow, a large bold title, an optional quiet subtitle, and an optional action on the right.
 */
@Composable
fun PageHeader(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
        Text(
            text = eyebrow.uppercase(),
            fontFamily = InterFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            letterSpacing = 1.6.sp,
            color = IvyOrange
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                letterSpacing = (-0.8).sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            trailing?.invoke()
        }
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = subtitle, fontFamily = InterFamily, fontSize = 15.sp, lineHeight = 21.sp, color = TextSecondary)
        }
    }
}
