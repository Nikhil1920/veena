package com.byanr.veena.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.R
import com.byanr.veena.android.data.LANGUAGES
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ui.VeenaLinks
import com.byanr.veena.android.ui.appVersionName
import com.byanr.veena.android.ui.emailFeedback
import com.byanr.veena.android.ui.openPlayStoreListing
import com.byanr.veena.android.ui.openUrl
import com.byanr.veena.android.ui.reportIssueOnGitHub
import com.byanr.veena.android.ui.shareVeena
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    onChangeLanguage: () -> Unit,
    onOpenVoiceKeyboard: () -> Unit,
) {
    val settings by appViewModel.settings.collectAsState()
    val context = LocalContext.current
    val colors = veenaColors
    val micMode = settings?.micMode ?: MicMode.HOLD
    val language = settings?.languageCode?.let(::languageByCode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            "Settings",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.text,
        )
        Spacer(Modifier.height(20.dp))

        SectionLabel("Microphone Behavior")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeCard(
                icon = Icons.Outlined.KeyboardVoice,
                title = "Hold to Record",
                subtitle = "Press and hold the mic, release to transcribe",
                selected = micMode == MicMode.HOLD,
                modifier = Modifier.weight(1f),
                onClick = { appViewModel.setMicMode(MicMode.HOLD) },
            )
            ModeCard(
                icon = Icons.Filled.TouchApp,
                title = "Tap to Record",
                subtitle = "Tap to start, tap again to transcribe",
                selected = micMode == MicMode.TAP,
                modifier = Modifier.weight(1f),
                onClick = { appViewModel.setMicMode(MicMode.TAP) },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "The voice keyboard's mic button works the same way.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("Language")
        Surface(
            color = colors.backgroundElement,
            shape = MaterialTheme.shapes.medium,
            onClick = onChangeLanguage,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        language?.name ?: "Select language",
                        fontSize = 16.sp,
                        color = colors.text,
                    )
                    if (language != null) {
                        Text(language.nativeName, fontSize = 13.sp, color = colors.textSecondary)
                    }
                }
                Text("Change", fontSize = 14.sp, color = AccentBlue, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel(stringResource(R.string.vk_settings_section))
        Surface(
            color = colors.backgroundElement,
            shape = MaterialTheme.shapes.medium,
            onClick = onOpenVoiceKeyboard,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.vk_settings_row_title),
                        fontSize = 16.sp,
                        color = colors.text,
                    )
                    Text(
                        stringResource(R.string.vk_settings_row_subtitle),
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                }
                Text(
                    stringResource(R.string.vk_settings_row_action),
                    fontSize = 14.sp,
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Downloaded Models")
        ModelCatalog.ALL.forEach { def ->
            val downloaded = appViewModel.models.isDownloaded(def)
            val installed = appViewModel.models.installedModels()
                .firstOrNull { it.def.id == def.id }
            Surface(
                color = colors.backgroundElement,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (def.multilingual) "IndicConformer Multilingual (22 languages)"
                            else "IndicConformer Telugu",
                            fontSize = 15.sp,
                            color = colors.text,
                        )
                        Text(
                            if (downloaded && installed != null) {
                                "On device · ${formatBytes(installed.sizeBytes)}"
                            } else {
                                "Not downloaded"
                            },
                            fontSize = 13.sp,
                            color = colors.textSecondary,
                        )
                    }
                    if (downloaded) {
                        TextButton(onClick = { appViewModel.models.delete(def) }) {
                            Text("Delete", color = Color(0xFFE5484D))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("About")
        Surface(
            color = colors.backgroundElement,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutRow("Version", remember { context.appVersionName() })
                AboutRow("Model", "IndicConformer (AI4Bharat), int8")
                AboutRow("Sample Rate", "16,000 Hz")
                AboutRow("Engine", "Rust · ONNX Runtime")
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Open Source")
        LinkGroup {
            Text(
                "Veena is free and open source under the MIT License. No ads, no " +
                    "account, and your voice never leaves your phone. Anyone can read " +
                    "the code, report problems, or contribute.",
                fontSize = 13.sp,
                color = colors.textSecondary,
                modifier = Modifier.padding(16.dp),
            )
            HorizontalDivider(color = colors.background)
            LinkRow(Icons.Outlined.Code, "Source code", "github.com/Nikhil1920/veena") {
                context.openUrl(VeenaLinks.SOURCE)
            }
            LinkDivider()
            LinkRow(Icons.Outlined.BugReport, "Report a bug or request a feature", "Open an issue on GitHub") {
                context.reportIssueOnGitHub()
            }
            LinkDivider()
            LinkRow(Icons.Outlined.Description, "License", "MIT License") {
                context.openUrl(VeenaLinks.LICENSE)
            }
            LinkDivider()
            LinkRow(Icons.Outlined.AutoAwesome, "Speech models", "IndicConformer by AI4Bharat") {
                context.openUrl(VeenaLinks.INDIC_CONFORMER)
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("Support Veena")
        LinkGroup {
            LinkRow(Icons.Outlined.StarOutline, "Rate Veena", "Leave a review on Google Play") {
                context.openPlayStoreListing()
                appViewModel.markRated()
            }
            LinkDivider()
            LinkRow(Icons.Outlined.Email, "Send feedback", VeenaLinks.EMAIL) {
                context.emailFeedback()
            }
            LinkDivider()
            LinkRow(Icons.Outlined.Share, "Share Veena", "Tell a friend") {
                context.shareVeena()
            }
            LinkDivider()
            LinkRow(Icons.Outlined.Language, "Website", "veena.byanr.com") {
                context.openUrl(VeenaLinks.WEBSITE)
            }
            LinkDivider()
            LinkRow(Icons.Outlined.PrivacyTip, "Privacy policy", null) {
                context.openUrl(VeenaLinks.PRIVACY)
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "Veena supports ${LANGUAGES.size} scheduled languages of India.",
            fontSize = 12.sp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000f)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000f)
    else -> "%.0f KB".format(bytes / 1_000f)
}

@Composable
private fun SectionLabel(text: String) {
    val colors = veenaColors
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
internal fun ModeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = veenaColors
    Surface(
        color = if (selected) colors.backgroundSelected else colors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
        modifier = modifier,
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (selected) AccentBlue else colors.backgroundSelected,
                    shape = CircleShape,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .padding(8.dp)
                            .size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = AccentBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, fontSize = 12.sp, color = colors.textSecondary)
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    val colors = veenaColors
    Row {
        Text(label, fontSize = 14.sp, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = colors.text)
    }
}

@Composable
private fun LinkGroup(content: @Composable () -> Unit) {
    Surface(
        color = veenaColors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    val colors = veenaColors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = colors.text)
            if (subtitle != null) {
                Text(subtitle, fontSize = 13.sp, color = colors.textSecondary)
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun LinkDivider() {
    HorizontalDivider(
        color = veenaColors.background,
        modifier = Modifier.padding(start = 50.dp),
    )
}
