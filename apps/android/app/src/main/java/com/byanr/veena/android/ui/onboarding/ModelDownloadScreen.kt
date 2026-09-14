package com.byanr.veena.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.data.DownloadState
import com.byanr.veena.android.data.ModelDef
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors

/** Shows download progress for a voice model, with retry/cancel handling. */
@Composable
fun ModelDownloadScreen(
    appViewModel: AppViewModel,
    def: ModelDef,
    headline: String,
    onSuccess: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by appViewModel.downloadStateFor(def).collectAsState()
    val colors = veenaColors

    // Kick the download off as soon as the screen opens.
    LaunchedEffect(def.id) {
        if (state == DownloadState.Idle || state is DownloadState.Failed ||
            state == DownloadState.Cancelled
        ) {
            appViewModel.download(def) { ok -> if (ok) onSuccess() }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Text(
            headline,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = colors.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (def.multilingual) "IndicConformer Multilingual · 22 languages"
            else "IndicConformer Telugu",
            fontSize = 14.sp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            is DownloadState.Downloading -> {
                val fraction = if (s.totalBytes > 0) {
                    s.downloadedBytes.toFloat() / s.totalBytes
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentBlue,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "${formatMb(s.downloadedBytes)} / ${formatMb(s.totalBytes)} MB",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            is DownloadState.Verifying -> {
                CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(12.dp))
                Text("Verifying…", fontSize = 13.sp, color = colors.textSecondary)
            }
            is DownloadState.Failed -> {
                Text(
                    "Download failed: ${s.message}",
                    fontSize = 14.sp,
                    color = androidx.compose.ui.graphics.Color(0xFFE5484D),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        appViewModel.download(def) { ok -> if (ok) onSuccess() }
                    },
                ) {
                    Text("Retry")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Back") }
            }
            is DownloadState.Cancelled -> {
                Text("Download cancelled", fontSize = 14.sp, color = colors.textSecondary)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        appViewModel.download(def) { ok -> if (ok) onSuccess() }
                    },
                ) {
                    Text("Retry")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Back") }
            }
            DownloadState.Idle -> {
                CircularProgressIndicator(color = AccentBlue, modifier = Modifier.size(32.dp))
            }
        }
    }
}

private fun formatMb(bytes: Long): String = "%.0f".format(bytes / 1_000_000f)
