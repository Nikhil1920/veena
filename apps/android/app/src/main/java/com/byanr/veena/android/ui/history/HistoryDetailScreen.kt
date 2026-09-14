package com.byanr.veena.android.ui.history

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byanr.veena.android.data.HistoryEntry
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.veenaColors
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryDetailScreen(
    entryId: Long,
    onBack: () -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val entry = entries.firstOrNull { it.id == entryId }
    val playingPath by viewModel.player.playingPath.collectAsState()
    val clipboard = LocalClipboardManager.current
    val colors = veenaColors

    if (entry == null) {
        // Deleted (or not yet loaded) — pop back.
        LaunchedEffect(entryId) { onBack() }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = colors.text,
                )
            }
            Text(
                "Recording",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.text,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                viewModel.delete(entry)
            }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = colors.textSecondary,
                )
            }
        }

        val dateFormat = remember {
            SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        }
        Text(
            dateFormat.format(Date(entry.createdAt)),
            fontSize = 13.sp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = colors.backgroundElement,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            Text(
                entry.transcript,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = colors.text,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            val audioExists = remember(entry.audioPath) {
                entry.audioPath.isNotBlank() && File(entry.audioPath).exists()
            }
            if (audioExists) {
                val playing = playingPath == entry.audioPath
                Button(
                    onClick = { viewModel.player.toggle(entry.audioPath) },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = CircleShape,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (playing) "Pause" else "Play")
                }
            }
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(entry.transcript)) },
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Copy")
            }
        }
    }
}
