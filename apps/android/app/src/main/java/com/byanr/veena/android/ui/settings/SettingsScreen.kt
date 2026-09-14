package com.byanr.veena.android.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.data.LANGUAGES
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

@Composable
fun SettingsScreen(
    appViewModel: AppViewModel,
    onChangeLanguage: () -> Unit,
) {
    val settings by appViewModel.settings.collectAsState()
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
                AboutRow("Model", "IndicConformer (AI4Bharat), int8")
                AboutRow("Sample Rate", "16,000 Hz")
                AboutRow("Engine", "Rust · ONNX Runtime")
                AboutRow("Version", "1.0.0")
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
private fun ModeCard(
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
