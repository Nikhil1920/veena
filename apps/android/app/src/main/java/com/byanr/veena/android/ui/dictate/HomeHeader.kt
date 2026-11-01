package com.byanr.veena.android.ui.dictate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.data.Language
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

/** What the status line shows; computed by the screen from engine and recording state. */
data class HeaderStatus(
    val dotColor: Color,
    val label: String,
    val pulsing: Boolean,
    /** Transcription progress, or null when unknown or not transcribing. */
    val progress: Float?,
    val transcribing: Boolean,
)

/**
 * Top of the Dictate screen: the title with a share button, then a card with
 * the engine status and the language switch. A thin bar along the card's
 * bottom edge tracks a running transcription.
 */
@Composable
fun HomeHeader(
    language: Language?,
    status: HeaderStatus,
    pulseAlpha: Float,
    onOpenLanguagePicker: () -> Unit,
    onShare: () -> Unit,
) {
    val colors = veenaColors
    Column {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Veena",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onShare,
                shape = CircleShape,
                color = colors.backgroundElement,
                contentColor = colors.text,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share Veena", modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Surface(
            color = colors.backgroundElement,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .alpha(if (status.pulsing) pulseAlpha else 1f)
                                .background(status.dotColor, CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(status.label, fontSize = 14.sp, color = colors.textSecondary, maxLines = 1)
                    }
                    language?.let {
                        Surface(
                            onClick = onOpenLanguagePicker,
                            shape = CircleShape,
                            color = colors.background,
                            contentColor = colors.text,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                Text(
                                    "${it.nativeName} · ${it.name}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "Change language",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                if (status.transcribing) {
                    val progress = status.progress
                    val barModifier = Modifier.fillMaxWidth().height(3.dp)
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            color = AccentBlue,
                            trackColor = colors.backgroundSelected,
                            modifier = barModifier,
                        )
                    } else {
                        LinearProgressIndicator(
                            color = AccentBlue,
                            trackColor = colors.backgroundSelected,
                            modifier = barModifier,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
