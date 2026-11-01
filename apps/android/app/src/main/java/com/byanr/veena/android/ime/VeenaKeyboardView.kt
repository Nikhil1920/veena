package com.byanr.veena.android.ime

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byanr.veena.android.R
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.StatusRecording
import com.byanr.veena.android.ui.theme.veenaColors
import kotlinx.coroutines.launch

/**
 * The voice-first keyboard panel: a large hold-to-talk mic flanked by a
 * globe (next keyboard) and backspace, with a language chip and a settings
 * gear on top. No letter keys in v1 — dictation is the whole keyboard.
 *
 * As [voiceInput] (another keyboard's mic key opened Veena) the panel starts
 * listening on its own while [autoStart] is set (and reports it through
 * [onAutoStarted], so the request is used once), the mic is always
 * tap-to-finish, and the globe becomes "back to your keyboard".
 *
 * The service supplies the side-effect callbacks ([onBackspace],
 * [onSwitchKeyboard], [onOpenVeena], [onCommit]) so this composable stays a
 * pure function of the [ImeSession] state.
 */
@Composable
fun VeenaKeyboardView(
    imeSession: ImeSession,
    sensitiveField: Boolean,
    onBackspace: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onOpenVeena: () -> Unit,
    onCommit: (String) -> Unit,
    voiceInput: Boolean = false,
    autoStart: Boolean = false,
    onAutoStarted: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val colors = veenaColors

    val state by imeSession.state.collectAsState()
    val languageCode by imeSession.languageCode.collectAsState()
    val preferredMicMode by imeSession.micMode.collectAsState()
    // Nobody is holding the key when another keyboard hands over: tap to finish.
    val micMode = if (voiceInput) MicMode.TAP else preferredMicMode

    // Gate card from the last failed startDictation; cleared on success.
    var gate by remember { mutableStateOf<StartOutcome?>(null) }

    val recording = state is ImeDictationState.Recording
    val busy = state is ImeDictationState.PreparingModel ||
        state is ImeDictationState.Transcribing
    val micEnabled = !sensitiveField && !recording && !busy
    // Read inside gesture callbacks so they always see the latest value without
    // restarting the pointerInput handler mid-press.
    val currentMicEnabled by rememberUpdatedState(micEnabled)
    val currentRecording by rememberUpdatedState(recording)
    val currentMicMode by rememberUpdatedState(micMode)

    fun startDictation() {
        if (!currentMicEnabled) return
        scope.launch {
            gate = when (val outcome = imeSession.startDictation()) {
                is StartOutcome.Started -> null
                is StartOutcome.Failed -> null // session state carries the error message
                is StartOutcome.MicUnavailable -> outcome
                is StartOutcome.ModelNotInstalled -> outcome
            }
        }
    }

    fun stopAndCommit() {
        // No recording guard: a release during PreparingModel must still stop
        // the recording startDictation begins right after; stopDictation
        // no-ops safely under the session mutex when nothing is recording.
        scope.launch {
            try {
                val text = imeSession.stopDictation()
                if (!text.isNullOrBlank()) onCommit(text)
            } catch (_: Throwable) {
                // stopDictation reports failures through session state; nothing to commit.
            } finally {
                imeSession.scheduleIdleUnload()
            }
        }
    }

    LaunchedEffect(autoStart) {
        if (voiceInput && autoStart) {
            onAutoStarted()
            startDictation()
        }
    }

    val micColor = when {
        recording -> StatusRecording
        micEnabled || state is ImeDictationState.Error -> AccentBlue
        else -> colors.backgroundSelected
    }

    val micModifier = if (micMode == MicMode.HOLD) {
        Modifier.pointerInput(micMode) {
            val slop = 24.dp.toPx()
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                if (!currentMicEnabled) return@awaitEachGesture
                startDictation()
                // Hold until the finger actually lifts; leaving the key cancels.
                var draggedOff = false
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.any { it.pressed }
                    if (pressed && !draggedOff) {
                        val position = event.changes.first().position
                        val inside = position.x >= -slop && position.x <= size.width + slop &&
                            position.y >= -slop && position.y <= size.height + slop
                        if (!inside) {
                            draggedOff = true
                            imeSession.cancelDictation()
                        }
                    }
                    if (!pressed) break
                }
                if (!draggedOff) stopAndCommit()
            }
        }
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.background)
            // Edge-to-edge IME windows (Android 15+) extend under the
            // navigation bar; keep the keys above its handle and buttons.
            .navigationBarsPadding()
            .height(PANEL_HEIGHT)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            LanguageChip(
                imeSession = imeSession,
                languageCode = languageCode,
                onOpenVeena = onOpenVeena,
            )
            Spacer(Modifier.weight(1f))
            RoundKey(
                icon = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.ime_open_settings),
                onClick = onOpenVeena,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when (val outcome = gate) {
                is StartOutcome.MicUnavailable -> GateCard(
                    title = stringResource(R.string.ime_mic_permission_needed),
                    onOpenVeena = onOpenVeena,
                )
                is StartOutcome.ModelNotInstalled -> GateCard(
                    title = stringResource(
                        R.string.ime_download_model_in_veena,
                        languageByCode(outcome.languageCode)?.name ?: outcome.languageCode,
                    ),
                    onOpenVeena = onOpenVeena,
                )
                is StartOutcome.Failed, is StartOutcome.Started, null -> {
                    if (sensitiveField) {
                        SensitiveChip()
                    } else {
                        StatusReadout(state, micMode)
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            RoundKey(
                icon = if (voiceInput) Icons.Filled.Keyboard else Icons.Filled.Language,
                contentDescription = stringResource(
                    if (voiceInput) R.string.ime_back_to_keyboard else R.string.ime_switch_keyboard
                ),
                onClick = onSwitchKeyboard,
            )
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                if (recording) {
                    Box(
                        Modifier
                            .size(88.dp)
                            .alpha(0.3f)
                            .background(StatusRecording, CircleShape)
                    )
                }
                Surface(
                    onClick = {
                        if (currentMicMode == MicMode.TAP) {
                            when {
                                currentRecording -> stopAndCommit()
                                currentMicEnabled -> startDictation()
                            }
                        }
                    },
                    shape = CircleShape,
                    color = micColor,
                    modifier = micModifier
                        .size(72.dp)
                        .alpha(if (micEnabled || recording) 1f else 0.5f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (state is ImeDictationState.Transcribing) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(28.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.ime_mic),
                                tint = Color.White,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
                if (state is ImeDictationState.PreparingModel) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(18.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            RoundKey(
                icon = Icons.Filled.Backspace,
                contentDescription = stringResource(R.string.ime_backspace),
                onClick = onBackspace,
            )
        }
    }
}

/** Chip showing the current language; opens a menu of downloaded languages. */
@Composable
private fun LanguageChip(
    imeSession: ImeSession,
    languageCode: String,
    onOpenVeena: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = veenaColors
    var open by remember { mutableStateOf(false) }

    Box {
        Surface(
            onClick = { open = true },
            shape = CircleShape,
            color = colors.backgroundElement,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                val language = languageByCode(languageCode)
                Text(
                    text = language?.let { "${it.nativeName} · ${it.name}" } ?: languageCode,
                    fontSize = 13.sp,
                    color = colors.text,
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.ime_change_language),
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Refresh on language change; the read touches the model directory.
            val codes = remember(languageCode) { imeSession.downloadedLanguages() }
            if (codes.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ime_no_models_downloaded),
                            fontSize = 13.sp,
                        )
                    },
                    onClick = {
                        open = false
                        onOpenVeena()
                    },
                )
            }
            for (code in codes) {
                val language = languageByCode(code)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = language?.let { "${it.nativeName} · ${it.name}" } ?: code,
                            fontSize = 13.sp,
                            color = if (code == languageCode) AccentBlue else colors.text,
                        )
                    },
                    onClick = {
                        open = false
                        if (code != languageCode) {
                            scope.launch {
                                imeSession.setLanguage(code)
                                ImeSubtypes.sync(context)
                            }
                        }
                    },
                )
            }
        }
    }
}

/** Small round icon key (globe, gear, backspace). */
@Composable
private fun RoundKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = veenaColors
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.backgroundElement,
        modifier = Modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Card shown when dictation cannot start; the button deep-links into Veena. */
@Composable
private fun GateCard(title: String, onOpenVeena: () -> Unit) {
    val colors = veenaColors
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.backgroundElement,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                title,
                fontSize = 13.sp,
                color = colors.text,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenVeena) {
                Text(stringResource(R.string.ime_open_veena), fontSize = 13.sp)
            }
        }
    }
}

/** Explains why the mic is disabled when the focused field is a password field. */
@Composable
private fun SensitiveChip() {
    val colors = veenaColors
    Surface(shape = CircleShape, color = colors.backgroundElement) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.ime_password_fields_off),
                fontSize = 12.sp,
                color = colors.textSecondary,
            )
        }
    }
}

/** The readout between the key rows: elapsed/level, progress states, errors, hints. */
@Composable
private fun StatusReadout(state: ImeDictationState, micMode: MicMode) {
    val colors = veenaColors
    when (val current = state) {
        is ImeDictationState.Recording -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatElapsed(current.elapsedMs),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusRecording,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    // Raw RMS rarely passes 0.25 in speech; gain it so the bar reads.
                    progress = { (current.level * LEVEL_GAIN).coerceIn(0f, 1f) },
                    modifier = Modifier.width(140.dp),
                )
            }
        }
        is ImeDictationState.Transcribing -> Text(
            stringResource(R.string.ime_transcribing),
            fontSize = 13.sp,
            color = colors.textSecondary,
        )
        is ImeDictationState.PreparingModel -> Text(
            stringResource(R.string.ime_preparing_model),
            fontSize = 13.sp,
            color = colors.textSecondary,
        )
        is ImeDictationState.Error -> Text(
            current.message,
            fontSize = 13.sp,
            color = StatusRecording,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        ImeDictationState.Idle -> Text(
            text = if (micMode == MicMode.HOLD) {
                stringResource(R.string.ime_hold_hint)
            } else {
                stringResource(R.string.ime_tap_hint)
            },
            fontSize = 12.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private val PANEL_HEIGHT = 232.dp

private const val LEVEL_GAIN = 4f
