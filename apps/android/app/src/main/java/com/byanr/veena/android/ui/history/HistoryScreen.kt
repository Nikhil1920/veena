package com.byanr.veena.android.ui.history

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byanr.veena.android.data.HistoryEntry
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ui.theme.StatusRecording
import com.byanr.veena.android.ui.theme.veenaColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    onOpenEntry: (Long) -> Unit,
    viewModel: HistoryViewModel = viewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val clipboard = LocalClipboardManager.current
    val colors = veenaColors
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDeleteAll by remember { mutableStateOf(false) }

    fun deleteWithUndo(entry: HistoryEntry) {
        viewModel.deleteRow(entry)
        scope.launch {
            // A newer delete replaces the pending Undo; dismissing it commits the older one.
            snackbar.currentSnackbarData?.dismiss()
            var undone = false
            try {
                undone = snackbar.showSnackbar(
                    message = "Recording deleted",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long,
                ) == SnackbarResult.ActionPerformed
            } finally {
                // Also runs if the screen leaves mid-snackbar, so audio is never orphaned.
                if (undone) viewModel.restore(entry) else viewModel.deleteAudio(entry)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            // Min height matches the Delete all button so the title doesn't jump when it hides.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(
                    "History",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                if (entries.isNotEmpty()) {
                    TextButton(onClick = { confirmDeleteAll = true }) {
                        Text("Delete all", color = colors.textSecondary)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (entries.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    Icon(
                        Icons.Outlined.History,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No recordings yet",
                        color = colors.textSecondary,
                        fontSize = 16.sp,
                    )
                    Text(
                        "Dictations and imported files will appear here",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        SwipeToDelete(onDelete = { deleteWithUndo(entry) }) {
                            HistoryRow(
                                entry = entry,
                                onClick = { onOpenEntry(entry.id) },
                                onCopy = {
                                    clipboard.setText(AnnotatedString(entry.transcript))
                                },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (confirmDeleteAll) {
        val count = entries.size
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = colors.backgroundElement,
            titleContentColor = colors.text,
            textContentColor = colors.textSecondary,
            title = { Text("Delete all recordings?") },
            text = {
                Text(
                    "This removes ${if (count == 1) "1 recording" else "all $count recordings"} " +
                        "and their audio from this phone. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    // Commit any pending swipe so its Undo can't bring one row back.
                    snackbar.currentSnackbarData?.dismiss()
                    viewModel.deleteAll()
                }) {
                    Text("Delete all", color = StatusRecording)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text("Cancel", color = colors.text)
                }
            },
        )
    }
}

/** Swipe a row right-to-left to delete it, revealing a red trash background. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Plain remember rather than rememberSwipeToDismissBoxState (rememberSaveable):
    // a row brought back by Undo must not come back in its swiped-away state.
    val state = remember {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, positionalThreshold = threshold)
    }
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .fillMaxSize()
                    .background(StatusRecording, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White)
            }
        },
    ) {
        content()
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, onClick: () -> Unit, onCopy: () -> Unit) {
    val colors = veenaColors
    val dateFormat = rememberSimpleDateFormat()

    Surface(
        color = colors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = colors.backgroundSelected,
                    shape = CircleShape,
                ) {
                    Text(
                        languageByCode(entry.languageCode)?.name ?: entry.languageCode,
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                if (entry.source == "import") {
                    Text("imported", fontSize = 11.sp, color = colors.textSecondary)
                } else if (entry.durationMs > 0) {
                    Text(
                        "${"%.1f".format(entry.durationMs / 1000f)}s",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    dateFormat.format(Date(entry.createdAt)),
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    entry.transcript,
                    fontSize = 15.sp,
                    color = colors.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "Copy transcript",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberSimpleDateFormat(): SimpleDateFormat {
    return androidx.compose.runtime.remember {
        SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
    }
}
