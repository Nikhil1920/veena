package com.byanr.veena.android.ui.conversation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.data.CONVERSATION_FONT_SIZES
import com.byanr.veena.android.data.ConversationItem
import com.byanr.veena.android.data.Conversations
import com.byanr.veena.android.data.SpeechLine
import com.byanr.veena.android.data.TypedReply
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.StatusRecording
import com.byanr.veena.android.ui.theme.VeenaColors
import com.byanr.veena.android.ui.theme.veenaColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * Live captions for a conversation, for people who are deaf or hard of
 * hearing: Veena listens while this screen is open and writes what people
 * say, a line per utterance, large enough to read at arm's length. Replies
 * can be typed and shown just as large.
 */
@Composable
fun ConversationScreen(
    appViewModel: AppViewModel,
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onOpenLanguagePicker: () -> Unit,
) {
    val context = LocalContext.current
    val colors = veenaColors
    val settings by appViewModel.settings.collectAsState()
    val languageCode = settings?.languageCode ?: return
    val fontSize = settings?.conversationFontSize ?: CONVERSATION_FONT_SIZES.first
    val saveConversations = settings?.saveConversations ?: true
    val language = languageByCode(languageCode)
    val languageName = language?.name ?: languageCode
    val chipLabel = language?.nativeName?.substringBefore(" / ") ?: languageCode.uppercase()

    val items by viewModel.items.collectAsState()
    val partial by viewModel.partial.collectAsState()
    val state by viewModel.state.collectAsState()
    val level by viewModel.level.collectAsState()

    var hasMic by remember { mutableStateOf(context.hasMicPermission()) }
    var askedForMic by rememberSaveable { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }
    LaunchedEffect(Unit) {
        if (!hasMic && !askedForMic) {
            askedForMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Listen only while the screen is on view; the conversation itself
    // lives on in the view model.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, languageCode, hasMic) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    // Granted or revoked in system settings meanwhile: the new
                    // key restarts this effect.
                    val granted = context.hasMicPermission()
                    if (granted != hasMic) hasMic = granted
                    else if (granted) viewModel.onVisible(languageCode)
                }
                Lifecycle.Event.ON_STOP -> viewModel.onHidden()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    // People read along at arm's length: don't let the screen sleep.
    val view = LocalView.current
    val awake = state is ListenState.Listening || state is ListenState.Loading
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    var composing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    BackHandler(enabled = composing) { composing = false }
    BackHandler(enabled = !composing, onBack = onBack)

    val listState = rememberLazyListState()
    // Follow new text unless the user scrolled up to read; back at the
    // bottom, following resumes.
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {
        if (dragged) follow = false else if (!listState.canScrollForward) follow = true
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { if (!it) follow = true }
    }
    LaunchedEffect(items.size, partial, state, follow, fontSize) {
        if (follow) listState.scrollToEnd()
    }
    // The keyboard opening shrinks the view: keep the latest text in it.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize }.collect {
            if (follow) listState.scrollToEnd(animate = false)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            // The NavHost already pads for the navigation bar.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    TranscriptItem(
                        item = item,
                        previous = items.getOrNull(index - 1),
                        fontSize = fontSize,
                        formatTime = viewModel::formatTime,
                    )
                }
                if (partial.isNotEmpty()) {
                    item(key = "partial") {
                        val previous = items.lastOrNull()
                        val now = remember(previous?.id) { System.currentTimeMillis() }
                        Column {
                            if (Conversations.showsTime(previous, now)) TimeHeader(viewModel.formatTime(now))
                            if (Conversations.startsSpeechRun(previous, now)) SpeechLabel()
                            SpeechText(partial, fontSize, colors.text.copy(alpha = 0.6f))
                        }
                    }
                }
                if (items.isEmpty() && partial.isEmpty()) {
                    item(key = "hint") {
                        Text(
                            "Start talking. Veena writes what people say, in $languageName.",
                            fontSize = 20.sp,
                            lineHeight = 28.sp,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(top = 32.dp, bottom = 16.dp),
                        )
                    }
                }
                item(key = "status") {
                    StatusRow(
                        state = state,
                        level = level,
                        hasMic = hasMic,
                        languageName = languageName,
                        onAllowMic = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                        onOpenAppSettings = { context.openAppSettings() },
                        onResume = viewModel::togglePause,
                        onClear = if (items.isNotEmpty()) ({ confirmClear = true }) else null,
                    )
                }
            }

            JumpToLatest(
                visible = !follow && listState.canScrollForward,
                onClick = { follow = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }

        if (composing) {
            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                onClear = { if (draft.isEmpty()) composing = false else draft = "" },
                onSend = {
                    viewModel.sendTyped(draft)
                    draft = ""
                    composing = false
                    keyboard?.hide()
                    follow = true
                },
            )
        } else {
            BottomBar(
                listening = state !is ListenState.Paused && state !is ListenState.Failed,
                chipLabel = chipLabel,
                onBack = onBack,
                onSettings = { showSettings = true },
                onTogglePause = viewModel::togglePause,
                onLanguage = onOpenLanguagePicker,
                onType = { composing = true },
            )
        }
    }

    if (confirmClear) {
        ClearConversationDialog(
            saveConversations = saveConversations,
            onConfirm = {
                confirmClear = false
                viewModel.clear()
            },
            onDismiss = { confirmClear = false },
        )
    }

    if (showSettings) {
        ConversationSettingsSheet(
            fontSize = fontSize,
            languageName = languageName,
            saveConversations = saveConversations,
            hasConversation = items.isNotEmpty(),
            onFontSize = appViewModel::setConversationFontSize,
            onLanguage = {
                showSettings = false
                onOpenLanguagePicker()
            },
            onSaveConversations = appViewModel::setSaveConversations,
            onShare = { context.shareText(viewModel.exportText()) },
            onCopy = { context.copyText(viewModel.exportText()) },
            onClear = {
                viewModel.clear()
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Scrolls to the end of the transcript (the last item is the status row).
 * A newer scroll interrupting this one is no failure: only cancelling the
 * caller ends it.
 */
private suspend fun LazyListState.scrollToEnd(animate: Boolean = true) {
    val last = layoutInfo.totalItemsCount - 1
    if (last < 0) return
    try {
        if (animate) animateScrollToItem(last) else scrollToItem(last)
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
    }
}

@Composable
private fun JumpToLatest(visible: Boolean, onClick: () -> Unit, modifier: Modifier) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Surface(onClick = onClick, color = AccentBlue, shape = RoundedCornerShape(24.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Jump to the latest",
                tint = Color.White,
                modifier = Modifier
                    .padding(horizontal = 32.dp, vertical = 12.dp)
                    .size(32.dp),
            )
        }
    }
}

@Composable
private fun TranscriptItem(
    item: ConversationItem,
    previous: ConversationItem?,
    fontSize: Int,
    formatTime: (Long) -> String,
) {
    val colors = veenaColors
    Column {
        if (Conversations.showsTime(previous, item.time)) TimeHeader(formatTime(item.time))
        when (item) {
            is SpeechLine -> {
                if (Conversations.startsSpeechRun(previous, item.time)) SpeechLabel()
                SpeechText(item.text, fontSize, colors.text)
            }
            is TypedReply -> TypedCard(item.text, fontSize)
        }
    }
}

@Composable
private fun TimeHeader(time: String) {
    Text(
        time,
        fontSize = 15.sp,
        color = veenaColors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 16.dp),
    )
}

@Composable
private fun SpeechLabel() {
    Text(
        "Speech transcription",
        fontSize = 14.sp,
        color = veenaColors.textSecondary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun SpeechText(text: String, fontSize: Int, color: Color) {
    Text(
        text,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.25f).sp,
        color = color,
        modifier = Modifier.padding(bottom = 16.dp),
    )
}

@Composable
private fun TypedCard(text: String, fontSize: Int) {
    val colors = veenaColors
    Surface(
        color = colors.backgroundElement,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 20.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Typed response", fontSize = 14.sp, color = colors.textSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                fontSize = fontSize.sp,
                lineHeight = (fontSize * 1.25f).sp,
                color = colors.text,
            )
        }
    }
}

@Composable
private fun StatusRow(
    state: ListenState,
    level: Float,
    hasMic: Boolean,
    languageName: String,
    onAllowMic: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onResume: () -> Unit,
    /** Shown while paused; null when there is nothing to clear. */
    onClear: (() -> Unit)?,
) {
    val colors = veenaColors
    val modifier = Modifier.padding(vertical = 12.dp)
    if (!hasMic) {
        Column(modifier) {
            Text(
                "Veena needs the microphone to write what people say.",
                fontSize = 18.sp,
                lineHeight = 26.sp,
                color = colors.text,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onAllowMic,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                ) { Text("Allow microphone") }
                TextButton(onClick = onOpenAppSettings) { Text("Open settings", color = AccentBlue) }
            }
        }
        return
    }
    when (state) {
        ListenState.Listening -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            LevelBars(level, colors.text)
            Spacer(Modifier.width(12.dp))
            Text("Listening…", fontSize = 18.sp, color = colors.text)
        }
        is ListenState.Loading -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = colors.text,
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("Loading the $languageName model…", fontSize = 18.sp, color = colors.textSecondary)
        }
        ListenState.Paused -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Pause, contentDescription = null, tint = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Text("Paused", fontSize = 18.sp, color = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onResume) { Text("Resume", fontSize = 16.sp, color = AccentBlue) }
            if (onClear != null) {
                TextButton(onClick = onClear) { Text("Clear", fontSize = 16.sp, color = StatusRecording) }
            }
        }
        is ListenState.Failed -> Column(modifier) {
            Text(state.message, fontSize = 16.sp, color = StatusRecording)
            TextButton(onClick = onResume) { Text("Try again", color = AccentBlue) }
        }
        ListenState.Stopped -> Spacer(Modifier.height(1.dp))
    }
}

/** Four bars that rise with the microphone level. */
@Composable
private fun LevelBars(level: Float, color: Color) {
    // -60 dBFS (a quiet room) to -10 dBFS (close, loud speech).
    val loudness = ((20f * log10(level.coerceAtLeast(1e-5f)) + 60f) / 50f).coerceIn(0f, 1f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(22.dp),
    ) {
        listOf(0.55f, 1f, 0.75f, 0.4f).forEach { share ->
            val height by animateDpAsState(4.dp + 18.dp * loudness * share, label = "levelBar")
            Box(
                Modifier
                    .width(4.dp)
                    .height(height)
                    .background(color, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun BottomBar(
    listening: Boolean,
    chipLabel: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTogglePause: () -> Unit,
    onLanguage: () -> Unit,
    onType: () -> Unit,
) {
    val colors = veenaColors
    Surface(color = colors.backgroundElement) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 8.dp),
        ) {
            BarButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
            BarButton(Icons.Filled.Settings, "Conversation settings", onSettings)
            BarButton(
                if (listening) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                if (listening) "Pause listening" else "Resume listening",
                onTogglePause,
            )
            Spacer(Modifier.width(4.dp))
            Surface(
                onClick = onLanguage,
                color = colors.backgroundSelected,
                shape = CircleShape,
            ) {
                Text(
                    chipLabel,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.text,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            BarButton(Icons.Outlined.Keyboard, "Type a reply", onType)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, contentDescription = description, tint = AccentBlue, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit,
) {
    val colors = veenaColors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(
        color = colors.backgroundElement,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 20.dp, bottom = 12.dp)) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, color = colors.text),
                cursorBrush = SolidColor(AccentBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp, max = 220.dp)
                    .focusRequester(focus),
                decorationBox = { field ->
                    Box {
                        if (draft.isEmpty()) {
                            Text("Type a reply", fontSize = 24.sp, color = colors.textSecondary)
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = if (draft.isEmpty()) "Close" else "Clear",
                        tint = AccentBlue,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSend,
                    enabled = draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    shape = CircleShape,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = "Show reply", modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSettingsSheet(
    fontSize: Int,
    languageName: String,
    saveConversations: Boolean,
    hasConversation: Boolean,
    onFontSize: (Int) -> Unit,
    onLanguage: () -> Unit,
    onSaveConversations: (Boolean) -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = veenaColors
    var confirmClear by remember { mutableStateOf(false) }
    val sizes = CONVERSATION_FONT_SIZES
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SheetCard(colors) {
                Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                    Text("Font size", fontSize = 16.sp, color = colors.text)
                    Text("Aa", fontSize = fontSize.sp, color = colors.text, modifier = Modifier.padding(vertical = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = { onFontSize(fontSize - STEP) },
                            enabled = fontSize > sizes.first,
                        ) { Icon(Icons.Filled.Remove, contentDescription = "Smaller text", tint = colors.text) }
                        Slider(
                            value = fontSize.toFloat(),
                            onValueChange = {
                                val size = sizes.first + Math.round((it - sizes.first) / STEP) * STEP
                                if (size != fontSize) onFontSize(size)
                            },
                            valueRange = sizes.first.toFloat()..sizes.last.toFloat(),
                            steps = (sizes.last - sizes.first) / STEP - 1,
                            colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onFontSize(fontSize + STEP) },
                            enabled = fontSize < sizes.last,
                        ) { Icon(Icons.Filled.Add, contentDescription = "Larger text", tint = colors.text) }
                    }
                }
            }
            SheetCard(colors) {
                SheetRow("Language", languageName, onClick = onLanguage)
            }
            SheetCard(colors) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onSaveConversations(!saveConversations) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Save conversations to History", fontSize = 16.sp, color = colors.text)
                        Text("Kept on this phone only", fontSize = 13.sp, color = colors.textSecondary)
                    }
                    Switch(
                        checked = saveConversations,
                        onCheckedChange = onSaveConversations,
                        colors = SwitchDefaults.colors(checkedTrackColor = AccentBlue),
                    )
                }
            }
            SheetCard(colors) {
                Column {
                    SheetAction(Icons.Outlined.Share, "Share conversation", hasConversation, colors.text, onShare)
                    SheetAction(Icons.Outlined.ContentCopy, "Copy conversation", hasConversation, colors.text, onCopy)
                    SheetAction(Icons.Outlined.Delete, "Clear conversation", hasConversation, StatusRecording) {
                        confirmClear = true
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ClearConversationDialog(
            saveConversations = saveConversations,
            onConfirm = {
                confirmClear = false
                onClear()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ClearConversationDialog(
    saveConversations: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear this conversation?") },
        text = {
            Text(
                if (saveConversations) {
                    "It stays in History. A new conversation starts here."
                } else {
                    "Saving to History is off, so this conversation will be gone."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Clear", color = StatusRecording) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = veenaColors.text) }
        },
    )
}

private const val STEP = 2

@Composable
private fun SheetCard(colors: VeenaColors, content: @Composable () -> Unit) {
    Surface(
        color = colors.backgroundElement,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun SheetRow(title: String, value: String, onClick: () -> Unit) {
    val colors = veenaColors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(title, fontSize = 16.sp, color = colors.text)
        Text(value, fontSize = 13.sp, color = colors.textSecondary)
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    val tint = if (enabled) color else veenaColors.textSecondary.copy(alpha = 0.5f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 16.sp, color = tint)
    }
}

private fun Context.hasMicPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun Context.shareText(text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    startActivity(Intent.createChooser(send, null))
}

/** Copies off the main thread: the clipboard service can stall it (an ANR in 1.1.1). */
private fun Context.copyText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val appContext = applicationContext
    CoroutineScope(Dispatchers.IO).launch {
        clipboard.setPrimaryClip(ClipData.newPlainText("Conversation", text))
        // Android 13+ confirms copies itself.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            withContext(Dispatchers.Main) {
                Toast.makeText(appContext, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
