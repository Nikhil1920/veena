package com.byanr.veena.android.ui.dictate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.ui.emailFeedback
import com.byanr.veena.android.ui.openPlayStoreListing
import com.byanr.veena.android.ui.reportIssueOnGitHub
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

private enum class ReviewStep { Ask, Rate, Feedback }

/**
 * "Enjoying Veena?" for people who dictate regularly. Happy users are asked to
 * rate Veena on Google Play; unhappy ones are pointed at GitHub or email.
 */
@Composable
internal fun ReviewPromptCard(
    onShown: () -> Unit,
    onRated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(ReviewStep.Ask) }
    LaunchedEffect(Unit) { onShown() }

    when (step) {
        ReviewStep.Ask -> PromptCard(
            icon = Icons.Outlined.FavoriteBorder,
            title = "Enjoying Veena?",
            subtitle = "Is dictation working well for you?",
            dismissLabel = "Not really",
            onDismissAction = { step = ReviewStep.Feedback },
            confirmLabel = "Yes!",
            onConfirm = { step = ReviewStep.Rate },
            onClose = onDismiss,
        )
        ReviewStep.Rate -> PromptCard(
            icon = Icons.Outlined.StarOutline,
            title = "Glad to hear it",
            subtitle = "Would you rate Veena on Google Play? It helps others find it.",
            dismissLabel = "Not now",
            onDismissAction = onDismiss,
            confirmLabel = "Rate",
            onConfirm = {
                context.openPlayStoreListing()
                onRated()
            },
            onClose = onDismiss,
        )
        ReviewStep.Feedback -> PromptCard(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = "Sorry about that",
            subtitle = "Tell us what went wrong so it can be fixed.",
            dismissLabel = "Email",
            onDismissAction = {
                context.emailFeedback()
                onDismiss()
            },
            confirmLabel = "Report on GitHub",
            onConfirm = {
                context.reportIssueOnGitHub()
                onDismiss()
            },
            onClose = onDismiss,
        )
    }
}

@Composable
private fun PromptCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    dismissLabel: String,
    onDismissAction: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = veenaColors
    Surface(
        color = colors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = AccentBlue,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                    )
                    Text(subtitle, fontSize = 13.sp, color = colors.textSecondary)
                }
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextButton(onClick = onDismissAction) {
                    Text(dismissLabel, color = colors.textSecondary)
                }
                TextButton(onClick = onConfirm) {
                    Text(confirmLabel, color = AccentBlue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
