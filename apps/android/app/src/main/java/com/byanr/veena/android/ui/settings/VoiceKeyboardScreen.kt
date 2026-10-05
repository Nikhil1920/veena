package com.byanr.veena.android.ui.settings

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.byanr.veena.android.AppViewModel
import com.byanr.veena.android.R
import com.byanr.veena.android.data.MicMode
import com.byanr.veena.android.data.ModelCatalog
import com.byanr.veena.android.data.languageByCode
import com.byanr.veena.android.ime.VeenaImeService
import com.byanr.veena.android.ui.theme.AccentBlue
import com.byanr.veena.android.ui.theme.StatusReady
import com.byanr.veena.android.ui.theme.veenaColors

/** Live state of the system gates the keyboard needs; re-checked on every resume. */
private data class ImeGateStatus(
    val imeEnabled: Boolean,
    val imeSelected: Boolean,
    val micGranted: Boolean,
)

private fun readGateStatus(context: Context): ImeGateStatus {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    // Compare ComponentName objects: system IME ids and DEFAULT_INPUT_METHOD
    // use the short flattenToShortString spelling, which never string-equals
    // the long form.
    val imeComponent = ComponentName(context, VeenaImeService::class.java)
    val defaultId = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
    )
    return ImeGateStatus(
        imeEnabled = imm.enabledInputMethodList.any { it.component == imeComponent },
        imeSelected = ComponentName.unflattenFromString(defaultId ?: "") == imeComponent,
        micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED,
    )
}

/**
 * Guided setup for the voice keyboard: walks the user through enabling the IME,
 * selecting it, granting the microphone and picking a dictation language, with
 * live status and a test field once the system gates are green.
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
    // the permission prompt refreshes the checklist.
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
        imeLanguage != null && appViewModel.models.installedModels()
            .any { it.def.id == ModelCatalog.modelForLanguage(imeLanguage).id }
    }

    val systemGatesGreen = gates.imeEnabled && gates.imeSelected && gates.micGranted
    val allDone = systemGatesGreen && modelInstalled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.vk_back),
            color = AccentBlue,
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(vertical = 6.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.vk_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.text,
        )
        Spacer(Modifier.height(6.dp))
        if (allDone) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = StatusReady,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.vk_ready),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = StatusReady,
                )
            }
        } else {
            Text(
                stringResource(R.string.vk_setup_incomplete),
                fontSize = 14.sp,
                color = colors.textSecondary,
            )
        }
        Spacer(Modifier.height(18.dp))

        StepRow(
            stepNumber = 1,
            title = stringResource(R.string.vk_step_enable_title),
            description = stringResource(R.string.vk_step_enable_description),
            done = gates.imeEnabled,
            buttonLabel = stringResource(R.string.vk_step_enable_button),
            onButtonClick = {
                context.startActivity(
                    Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
        )
        StepRow(
            stepNumber = 2,
            title = stringResource(R.string.vk_step_select_title),
            description = stringResource(R.string.vk_step_select_description),
            done = gates.imeSelected,
            buttonLabel = stringResource(R.string.vk_step_select_button),
            onButtonClick = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
        )
        StepRow(
            stepNumber = 3,
            title = stringResource(R.string.vk_step_mic_title),
            description = stringResource(R.string.vk_step_mic_description),
            done = gates.micGranted,
            buttonLabel = stringResource(R.string.vk_step_mic_button),
            onButtonClick = {
                context.findActivity()?.let { activity ->
                    ActivityCompat.requestPermissions(
                        activity,
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        MIC_PERMISSION_REQUEST,
                    )
                }
            },
        )
        StepRow(
            stepNumber = 4,
            title = stringResource(R.string.vk_step_language_title),
            description = if (modelInstalled) {
                stringResource(R.string.vk_step_language_ready, imeLanguageName)
            } else {
                stringResource(R.string.vk_step_language_missing, imeLanguageName)
            },
            done = modelInstalled,
            buttonLabel = stringResource(R.string.vk_step_language_button),
            onButtonClick = onAddLanguage,
            showButtonWhenDone = true,
        )

        MicModeRow(
            micMode = settings?.imeMicMode ?: MicMode.HOLD,
            onModeChange = { appViewModel.setImeMicMode(it) },
        )

        if (systemGatesGreen) {
            Spacer(Modifier.height(16.dp))
            var tryText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = tryText,
                onValueChange = { tryText = it },
                label = { Text(stringResource(R.string.vk_try_label)) },
                supportingText = { Text(stringResource(R.string.vk_try_helper)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepRow(
    stepNumber: Int,
    title: String,
    description: String,
    done: Boolean,
    buttonLabel: String,
    onButtonClick: () -> Unit,
    showButtonWhenDone: Boolean = false,
) {
    val colors = veenaColors
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
            Box(
                Modifier
                    .size(24.dp)
                    .background(if (done) StatusReady else colors.backgroundSelected, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Text(
                        stepNumber.toString(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text,
                )
                Spacer(Modifier.height(4.dp))
                Text(description, fontSize = 13.sp, color = colors.textSecondary)
            }
            if (!done || showButtonWhenDone) {
                if (done) Spacer(Modifier.width(8.dp))
                TextButton(onClick = onButtonClick) {
                    Text(
                        buttonLabel,
                        fontSize = 14.sp,
                        color = AccentBlue,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/** Hold-to-talk vs tap-to-speak for the keyboard's mic button; the button names the mode it switches to. */
@Composable
private fun MicModeRow(
    micMode: MicMode,
    onModeChange: (MicMode) -> Unit,
) {
    val colors = veenaColors
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
                    stringResource(R.string.vk_mic_mode_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(
                        if (micMode == MicMode.HOLD) {
                            R.string.vk_mic_mode_hold
                        } else {
                            R.string.vk_mic_mode_tap
                        }
                    ) + " · " + stringResource(R.string.vk_mic_mode_description),
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            TextButton(onClick = {
                onModeChange(if (micMode == MicMode.HOLD) MicMode.TAP else MicMode.HOLD)
            }) {
                Text(
                    stringResource(
                        if (micMode == MicMode.HOLD) {
                            R.string.vk_mic_mode_tap
                        } else {
                            R.string.vk_mic_mode_hold
                        }
                    ),
                    fontSize = 14.sp,
                    color = AccentBlue,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private const val MIC_PERMISSION_REQUEST = 41
