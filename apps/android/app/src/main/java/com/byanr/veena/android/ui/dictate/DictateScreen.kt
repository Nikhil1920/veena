package com.byanr.veena.android.ui.dictate

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.engine.EngineState
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.StatusInitializing
import com.byanr.veena.android.ui.theme.StatusReady
import com.byanr.veena.android.ui.theme.StatusRecording
import com.byanr.veena.android.ui.theme.veenaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DictateScreen(
    appViewModel: AppViewModel,
    onOpenLanguagePicker: () -> Unit,
    viewModel: DictateViewModel = viewModel(),
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val colors = veenaColors

    val engineState by viewModel.engineState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val isTranscribing by viewModel.isTranscribing.collectAsState()
    val text by viewModel.text.collectAsState()
    val textRevision by viewModel.textRevision.collectAsState()
    val lastRecording by viewModel.lastRecording.collectAsState()
    val error by viewModel.error.collectAsState()
    val playingPath by viewModel.player.playingPath.collectAsState()
    val settings by appViewModel.settings.collectAsState()
    val micMode = settings?.micMode ?: MicMode.HOLD
    // Read inside gesture callbacks so they always see the latest value without
    // restarting the pointerInput handler mid-press.
    val currentMicMode by rememberUpdatedState(micMode)

    val snackbar = remember { SnackbarHostState() }

    // Elapsed recording time, shown in the status badge while recording.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                elapsedMs = System.currentTimeMillis() - startedAt
                delay(200)
            }
        } else {
            elapsedMs = 0L
        }
    }

    // Gentle alpha pulse for recording indicators (status dot + mic ring).
    val pulseAlpha by rememberInfiniteTransition(label = "recordingPulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 650, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "recordingPulseAlpha",
    )

    val textScrollState = rememberScrollState()
    // Follow new dictation output: when the app appends a transcript, scroll so
    // the latest text stays visible. Manual edits don't move the view.
    LaunchedEffect(textRevision) {
        if (textRevision > 0) {
            withFrameNanos { }
            textScrollState.animateScrollTo(textScrollState.maxValue)
        }
    }

    fun stopAndTranscribe() {
        scope.launch { viewModel.stopRecordingAndTranscribe() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // In HOLD mode the finger that triggered the prompt is long gone, so
            // auto-starting would record with nothing to stop it — only TAP auto-starts.
            if (currentMicMode == MicMode.TAP) {
                scope.launch {
                    try {
                        viewModel.startRecording()
                    } catch (t: Throwable) {
                        viewModel.error.value = t.message ?: "Failed to start recording"
                    }
                }
            }
        } else {
            viewModel.error.value = "Microphone permission is required for dictation"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(viewModel::transcribeImportedFile)
    }

    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    fun beginRecording() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            scope.launch {
                try {
                    viewModel.startRecording()
                } catch (t: Throwable) {
                    viewModel.error.value = t.message ?: "Failed to start recording"
                }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text(
                text = "Veena",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            settings?.languageCode?.let(::languageByCode)?.let { language ->
                Spacer(Modifier.height(6.dp))
                Surface(
                    onClick = onOpenLanguagePicker,
                    shape = CircleShape,
                    color = colors.backgroundElement,
                    contentColor = colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                    ) {
                        Text(
                            text = "${language.nativeName} · ${language.name}",
                            fontSize = 13.sp,
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
            Spacer(Modifier.height(12.dp))

            val (dotColor, statusLabel) = when {
                isRecording -> StatusRecording to "Recording · ${formatDuration(elapsedMs)}"
                isTranscribing -> StatusInitializing to "Transcribing..."
                engineState is EngineState.Ready -> StatusReady to "Ready"
                engineState is EngineState.Error -> StatusRecording to "Model failed to load"
                else -> StatusInitializing to "Loading model..."
            }
            Surface(
                shape = CircleShape,
                color = colors.backgroundElement,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .alpha(if (isRecording) pulseAlpha else 1f)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(statusLabel, fontSize = 13.sp, color = colors.textSecondary)
                }
            }
            Spacer(Modifier.height(16.dp))

            Surface(
                color = colors.backgroundElement,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .verticalScroll(textScrollState)
                ) {
                        BasicTextField(
                            value = text,
                            onValueChange = viewModel::setText,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = colors.text,
                                lineHeight = 24.sp,
                            ),
                            cursorBrush = SolidColor(AccentBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            decorationBox = { inner ->
                                if (text.isEmpty()) {
                                    Text(
                                        "Transcribed text will appear here...",
                                        color = Color(0xFF999999),
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(text)) },
                            enabled = text.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                        ) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Copy")
                        }
                        TextButton(
                            onClick = viewModel::clearText,
                            enabled = text.isNotEmpty(),
                            colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Clear")
                        }
                    }
                }
            }

            lastRecording?.let { recording ->
                if (recording.file.exists()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = AccentBlue,
                        shape = CircleShape,
                        onClick = { viewModel.player.toggle(recording.file.absolutePath) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            val playing = playingPath == recording.file.absolutePath
                            Icon(
                                imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (playing) {
                                    "Pause Audio"
                                } else {
                                    "Play Last Recording · ${formatDuration(recording.durationMs)}"
                                },
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    Surface(
                        onClick = { importLauncher.launch(arrayOf("audio/*")) },
                        shape = CircleShape,
                        color = colors.backgroundElement,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = "Import recording",
                                tint = colors.textSecondary,
                            )
                        }
                    }

                    val micEnabled =
                        engineState is EngineState.Ready && !isTranscribing && !isRecording
                    // Key on the mode only: keying on state that flips when recording
                    // starts would tear this handler down mid-press.
                    val micModifier = if (micMode == MicMode.HOLD) {
                        Modifier.pointerInput(micMode) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                if (
                                    engineState !is EngineState.Ready ||
                                    viewModel.isTranscribing.value ||
                                    currentMicMode != MicMode.HOLD
                                ) {
                                    return@awaitEachGesture
                                }
                                beginRecording()
                                // Hold until the finger actually lifts. Deliberately
                                // ignore event consumption by the Surface's own click
                                // handling — only a real release ends the hold.
                                while (currentEvent.changes.any { it.pressed }) {
                                    awaitPointerEvent()
                                }
                                stopAndTranscribe()
                            }
                        }
                    } else {
                        Modifier
                    }

                    Box(contentAlignment = Alignment.Center) {
                        if (isRecording) {
                            // Pulsing halo behind the mic while recording.
                            Box(
                                Modifier
                                    .size(94.dp)
                                    .alpha(pulseAlpha * 0.35f)
                                    .background(StatusRecording, CircleShape)
                            )
                        }
                        Surface(
                            onClick = {
                                if (micMode == MicMode.TAP) {
                                    if (isRecording) {
                                        stopAndTranscribe()
                                    } else if (micEnabled) {
                                        beginRecording()
                                    }
                                }
                            },
                            shape = CircleShape,
                            color = when {
                                isRecording -> StatusRecording
                                engineState is EngineState.Ready -> AccentBlue
                                else -> colors.backgroundSelected
                            },
                            modifier = micModifier
                                .size(72.dp)
                                .alpha(if (micEnabled || isRecording) 1f else 0.5f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isTranscribing) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(28.dp),
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Mic,
                                        contentDescription = "Microphone",
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = when {
                        micMode == MicMode.HOLD && isRecording -> "Release to transcribe"
                        micMode == MicMode.HOLD -> "Hold to record, release to transcribe"
                        isRecording -> "Tap again to stop"
                        else -> "Tap to record & transcribe"
                    },
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
