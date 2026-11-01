package com.byanr.veena.android.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardVoice
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.R
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ime.ImeSession
import com.byanr.veena.android.ime.VeenaImeService
import com.byanr.veena.android.ime.VeenaVoiceInputService
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.StatusReady
import com.byanr.veena.android.ui.theme.StatusRecording
import com.byanr.veena.android.ui.theme.veenaColors

/** Live state of the system gates the keyboard needs; re-checked on every resume. */
private data class ImeGateStatus(
    val imeEnabled: Boolean,
    val imeSelected: Boolean,
    val voiceInputEnabled: Boolean,
    val micGranted: Boolean,
)

private fun readGateStatus(context: Context): ImeGateStatus {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // Compare ComponentName objects: system IME ids and DEFAULT_INPUT_METHOD
    // use the short flattenToShortString spelling, which never string-equals
    // the long form.
    val imeComponent = ComponentName(context, VeenaImeService::class.java)
    val voiceComponent = ComponentName(context, VeenaVoiceInputService::class.java)
    val enabled = imm.enabledInputMethodList.map { it.component }
    val defaultId = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
    )
    return ImeGateStatus(
        imeEnabled = imeComponent in enabled,
        imeSelected = ComponentName.unflattenFromString(defaultId ?: "") == imeComponent,
        voiceInputEnabled = voiceComponent in enabled,
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )
}

/** True once the keyboard can dictate: on, allowed the mic, and its model is on the device. */
fun isVoiceKeyboardReady(context: Context, appViewModel: AppViewModel): Boolean {
    val gates = readGateStatus(context)
    val settings = appViewModel.settings.value
    val language = settings?.imeLanguageCode ?: settings?.languageCode ?: return false
    return gates.imeEnabled && gates.micGranted &&
        appViewModel.models.isDownloaded(ModelCatalog.modelForLanguage(language))
}

private enum class TourStep { WELCOME, ENABLE, MICROPHONE, LANGUAGE, TRY, MIC_KEY, DONE }

/** Steps that count toward "Step n of m". */
private val NUMBERED_STEPS = listOf(
    TourStep.ENABLE, TourStep.MICROPHONE, TourStep.LANGUAGE, TourStep.TRY, TourStep.MIC_KEY,
)

/**
 * Guided tour for the voice keyboard: what it does, turning it on in the
 * system keyboard settings, the microphone, the keyboard's language, a live
 * test field, and optionally the voice input other keyboards' mic keys open.
 * Every step shows live status, re-checked whenever the user comes back from
 * a system screen; reopening the tour starts at the first unfinished step.
 */
@Composable
fun VoiceKeyboardScreen(
    appViewModel: AppViewModel,
    onBack: () -> Unit,
    onAddLanguage: () -> Unit,
) {
    val context = LocalContext.current
    val colors = veenaColors
    val settings by appViewModel.settings.collectAsState()

    // Bumped on every resume so returning from the system keyboard settings or
    // the permission prompt refreshes the status.
    var statusTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) statusTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val gates = remember(statusTick) { readGateStatus(context) }
    val imeLanguage = settings?.imeLanguageCode ?: settings?.languageCode
    val imeLanguageName = imeLanguage?.let { languageByCode(it)?.name ?: it }
        ?: stringResource(R.string.vk_no_language)
    val modelInstalled = remember(imeLanguage, statusTick) {
        imeLanguage != null && appViewModel.models.isDownloaded(ModelCatalog.modelForLanguage(imeLanguage))
    }
    val completedDictations by ImeSession.completedDictations.collectAsState()
    val dictationsAtStart = rememberSaveable { completedDictations }

    // First visits start at the welcome; returning users land on the first
    // step still to do, or on the summary once everything is set.
    var step by rememberSaveable {
        mutableStateOf(
            when {
                !gates.imeEnabled -> TourStep.WELCOME
                !gates.micGranted -> TourStep.MICROPHONE
                !modelInstalled -> TourStep.LANGUAGE
                else -> TourStep.DONE
            }
        )
    }
    fun go(next: TourStep) {
        step = next
    }
    fun advance() {
        TourStep.entries.getOrNull(step.ordinal + 1)?.let(::go)
    }

    var micDenied by remember { mutableStateOf(false) }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusTick++
        micDenied = !granted
        if (granted) advance()
    }

    fun openKeyboardSettings() {
        context.startActivity(
            Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun showKeyboardPicker() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.vk_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.vk_back),
                        tint = colors.textSecondary,
                    )
                }
            }
            if (step in NUMBERED_STEPS) {
                Spacer(Modifier.height(10.dp))
                StepProgress(NUMBERED_STEPS.indexOf(step) + 1, NUMBERED_STEPS.size)
            }
            Spacer(Modifier.height(24.dp))

            when (step) {
                TourStep.WELCOME -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(Icons.Filled.Keyboard)
                        IconBadge(Icons.Filled.Mic)
                    }
                    StepTitle(stringResource(R.string.vk_tour_welcome_title))
                    StepBody(stringResource(R.string.vk_tour_welcome_body))
                    Spacer(Modifier.height(16.dp))
                    Bullet(Icons.Filled.WifiOff, stringResource(R.string.vk_tour_welcome_offline))
                    Bullet(Icons.Filled.Translate, stringResource(R.string.vk_tour_welcome_languages))
                    Bullet(Icons.Filled.Lock, stringResource(R.string.vk_tour_welcome_private))
                }

                TourStep.ENABLE -> {
                    IconBadge(Icons.Filled.Keyboard)
                    StepTitle(stringResource(R.string.vk_tour_enable_title))
                    StepBody(stringResource(R.string.vk_tour_enable_body))
                    Spacer(Modifier.height(16.dp))
                    SettingsMock(
                        rows = listOf(
                            stringResource(R.string.ime_name) to true,
                            stringResource(R.string.ime_voice_input_name) to false,
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusLine(stringResource(R.string.vk_tour_enable_status), gates.imeEnabled)
                }

                TourStep.MICROPHONE -> {
                    IconBadge(Icons.Filled.Mic)
                    StepTitle(stringResource(R.string.vk_tour_mic_title))
                    StepBody(stringResource(R.string.vk_tour_mic_body))
                    Spacer(Modifier.height(16.dp))
                    StatusLine(
                        stringResource(R.string.vk_tour_mic_status),
                        gates.micGranted,
                        failed = micDenied && !gates.micGranted,
                        note = if (micDenied && !gates.micGranted) {
                            stringResource(R.string.vk_tour_mic_denied)
                        } else {
                            null
                        },
                    )
                }

                TourStep.LANGUAGE -> {
                    IconBadge(Icons.Filled.Language)
                    StepTitle(stringResource(R.string.vk_tour_language_title))
                    StepBody(stringResource(R.string.vk_tour_language_body))
                    Spacer(Modifier.height(16.dp))
                    StatusLine(
                        if (modelInstalled) {
                            stringResource(R.string.vk_step_language_ready, imeLanguageName)
                        } else {
                            stringResource(R.string.vk_step_language_missing, imeLanguageName)
                        },
                        modelInstalled,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onAddLanguage) {
                        Text(
                            stringResource(R.string.vk_step_language_button),
                            color = AccentBlue,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                TourStep.TRY -> {
                    IconBadge(Icons.Filled.KeyboardVoice)
                    StepTitle(stringResource(R.string.vk_tour_try_title))
                    StepBody(stringResource(R.string.vk_tour_try_body))
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = ::showKeyboardPicker) {
                        Text(
                            stringResource(R.string.vk_tour_try_choose),
                            color = AccentBlue,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    var tryText by rememberSaveable { mutableStateOf("") }
                    OutlinedTextField(
                        value = tryText,
                        onValueChange = {
                            tryText = it
                            statusTick++ // The picker doesn't pause us; re-read the selection.
                        },
                        label = { Text(stringResource(R.string.vk_try_label)) },
                        placeholder = { Text(stringResource(R.string.vk_tour_try_field)) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusLine(stringResource(R.string.vk_tour_try_selected), gates.imeSelected)
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        stringResource(R.string.vk_tour_try_works),
                        completedDictations > dictationsAtStart,
                    )
                }

                TourStep.MIC_KEY -> {
                    IconBadge(Icons.Filled.KeyboardVoice)
                    StepTitle(stringResource(R.string.vk_tour_mickey_title))
                    StepBody(stringResource(R.string.vk_tour_mickey_body))
                    Spacer(Modifier.height(16.dp))
                    SettingsMock(
                        rows = listOf(
                            stringResource(R.string.ime_name) to true,
                            stringResource(R.string.ime_voice_input_name) to true,
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    StatusLine(stringResource(R.string.vk_tour_mickey_status), gates.voiceInputEnabled)
                    Spacer(Modifier.height(12.dp))
                    StepBody(stringResource(R.string.vk_tour_mickey_note))
                }

                TourStep.DONE -> {
                    IconBadge(Icons.Filled.Check, background = StatusReady)
                    StepTitle(stringResource(R.string.vk_tour_done_title))
                    StepBody(stringResource(R.string.vk_tour_done_body))
                    Spacer(Modifier.height(16.dp))
                    StatusLine(stringResource(R.string.vk_tour_enable_status), gates.imeEnabled)
                    Spacer(Modifier.height(8.dp))
                    StatusLine(stringResource(R.string.vk_tour_mic_status), gates.micGranted)
                    Spacer(Modifier.height(8.dp))
                    StatusLine(
                        stringResource(R.string.vk_step_language_ready, imeLanguageName),
                        modelInstalled,
                    )
                    Spacer(Modifier.height(8.dp))
                    StatusLine(stringResource(R.string.vk_tour_mickey_status), gates.voiceInputEnabled)
                    Spacer(Modifier.height(20.dp))
                    MicModeChoice(
                        micMode = settings?.micMode ?: MicMode.HOLD,
                        onModeChange = { appViewModel.setMicMode(it) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Footer: the step's main action, then Back.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            when (step) {
                TourStep.WELCOME -> PrimaryButton(stringResource(R.string.vk_tour_start)) { advance() }
                TourStep.ENABLE -> if (gates.imeEnabled) {
                    PrimaryButton(stringResource(R.string.vk_tour_next)) { advance() }
                } else {
                    PrimaryButton(stringResource(R.string.vk_step_enable_button), ::openKeyboardSettings)
                }
                TourStep.MICROPHONE -> when {
                    gates.micGranted -> PrimaryButton(stringResource(R.string.vk_tour_next)) { advance() }
                    micDenied -> PrimaryButton(stringResource(R.string.vk_tour_mic_open_settings), ::openAppSettings)
                    else -> PrimaryButton(stringResource(R.string.vk_step_mic_button)) {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                TourStep.LANGUAGE -> if (modelInstalled) {
                    PrimaryButton(stringResource(R.string.vk_tour_next)) { advance() }
                } else {
                    PrimaryButton(stringResource(R.string.vk_step_language_button), onAddLanguage)
                }
                TourStep.TRY -> PrimaryButton(stringResource(R.string.vk_tour_next)) { advance() }
                TourStep.MIC_KEY -> if (gates.voiceInputEnabled) {
                    PrimaryButton(stringResource(R.string.vk_tour_next)) { advance() }
                } else {
                    PrimaryButton(stringResource(R.string.vk_step_enable_button), ::openKeyboardSettings)
                    TextButton(onClick = { advance() }) {
                        Text(stringResource(R.string.vk_tour_skip), color = AccentBlue)
                    }
                }
                TourStep.DONE -> PrimaryButton(stringResource(R.string.vk_tour_done), onBack)
            }
            if (step != TourStep.WELCOME) {
                TextButton(onClick = { TourStep.entries.getOrNull(step.ordinal - 1)?.let(::go) }) {
                    Text(stringResource(R.string.vk_back), color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun StepProgress(current: Int, total: Int) {
    val colors = veenaColors
    Text(
        stringResource(R.string.vk_tour_step, current, total),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = colors.textSecondary,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (index in 1..total) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(
                        if (index <= current) AccentBlue else colors.backgroundSelected,
                        RoundedCornerShape(2.dp),
                    )
            )
        }
    }
}

@Composable
private fun IconBadge(icon: ImageVector, background: Color = AccentBlue) {
    Box(
        Modifier
            .size(56.dp)
            .background(background, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun StepTitle(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = veenaColors.text, lineHeight = 30.sp)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun StepBody(text: String) {
    Text(text, fontSize = 15.sp, color = veenaColors.textSecondary, lineHeight = 22.sp)
}

@Composable
private fun Bullet(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AccentBlue, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 14.sp, color = veenaColors.text)
    }
}

/** A check (done), an empty circle (to do) or a red cross (refused), with an optional hint. */
@Composable
private fun StatusLine(label: String, done: Boolean, failed: Boolean = false, note: String? = null) {
    val colors = veenaColors
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(22.dp)
                .background(
                    when {
                        done -> StatusReady
                        failed -> StatusRecording
                        else -> colors.backgroundSelected
                    },
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (done || failed) {
                Icon(
                    if (done) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            if (note != null) {
                Text(note, fontSize = 13.sp, color = colors.textSecondary)
            }
        }
    }
}

/** A drawing of the system keyboard list with the switches to turn on. */
@Composable
private fun SettingsMock(rows: List<Pair<String, Boolean>>) {
    val colors = veenaColors
    Surface(
        color = colors.backgroundElement,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            rows.forEach { (label, on) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(label, fontSize = 15.sp, color = colors.text, modifier = Modifier.weight(1f))
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(24.dp)
                            .background(if (on) AccentBlue else colors.backgroundSelected, CircleShape),
                        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(
                            Modifier
                                .padding(3.dp)
                                .size(18.dp)
                                .background(Color.White, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Hold vs tap for the mic button. It is the app's Microphone Behavior
 * setting, shown here with the same cards because the keyboard follows it.
 */
@Composable
private fun MicModeChoice(
    micMode: MicMode,
    onModeChange: (MicMode) -> Unit,
) {
    val colors = veenaColors
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.vk_mic_mode_title),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.vk_mic_mode_description),
            fontSize = 13.sp,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModeCard(
                icon = Icons.Outlined.KeyboardVoice,
                title = stringResource(R.string.vk_mic_mode_hold),
                subtitle = stringResource(R.string.vk_mic_mode_hold_detail),
                selected = micMode == MicMode.HOLD,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(MicMode.HOLD) },
            )
            ModeCard(
                icon = Icons.Filled.TouchApp,
                title = stringResource(R.string.vk_mic_mode_tap),
                subtitle = stringResource(R.string.vk_mic_mode_tap_detail),
                selected = micMode == MicMode.TAP,
                modifier = Modifier.weight(1f),
                onClick = { onModeChange(MicMode.TAP) },
            )
        }
    }
}
