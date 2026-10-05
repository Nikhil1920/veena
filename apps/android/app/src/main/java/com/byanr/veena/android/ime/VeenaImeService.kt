package com.byanr.veena.android.ime

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.byanr.veena.android.MainActivity
import com.byanr.veena.android.VeenaApplication
import com.byanr.veena.android.ui.theme.VeenaTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Veena voice keyboard. Hosts the Compose input view by acting as the
 * LifecycleOwner/ViewModelStoreOwner/SavedStateRegistryOwner the ComposeView
 * expects — an InputMethodService owns no Activity, so the view tree owners
 * must be wired by hand.
 *
 * One [ImeSession] is built for the service lifetime; the engine is loaded
 * lazily on the first mic press and unloaded again after an idle timeout.
 */
class VeenaImeService :
    InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val imeViewModelStore = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    private lateinit var imeSession: ImeSession

    /** True while the focused field is password-style; the mic must stay off. */
    private val sensitiveField = MutableStateFlow(false)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore get() = imeViewModelStore

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val container = (application as VeenaApplication).container
        imeSession = ImeSession(this, container.engine, container.models, container.prefs)
        ImeSubtypes.sync(this)
    }

    override fun onDestroy() {
        imeSession.shutdown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        imeViewModelStore.clear()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Compose needs the view-tree owners on the ComposeView itself AND on
        // the IME Dialog's decor view: the window recomposer resolves the
        // lifecycle from the decor, and crashes at first attach without it.
        val decor = window?.window?.decorView
        decor?.setViewTreeLifecycleOwner(this)
        decor?.setViewTreeViewModelStoreOwner(this)
        decor?.setViewTreeSavedStateRegistryOwner(this)

        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent {
            VeenaTheme {
                val sensitive by sensitiveField.collectAsState()
                VeenaKeyboardView(
                    imeSession = imeSession,
                    sensitiveField = sensitive,
                    onBackspace = ::deleteBackward,
                    onSwitchKeyboard = ::switchToNextKeyboard,
                    onOpenVeena = ::openVeena,
                    onCommit = ::commitDictation,
                )
            }
        }
        return view
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        super.onStartInputView(editorInfo, restarting)
        sensitiveField.value = isSensitiveField(editorInfo.inputType)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        imeSession.cancelDictation() // A hidden keyboard must never orphan the mic.
        imeSession.scheduleIdleUnload()
    }

    private fun deleteBackward() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun commitDictation(text: String) {
        val connection = currentInputConnection ?: return
        connection.commitText(text.trimEnd() + " ", 1)
        connection.finishComposingText()
    }

    private fun switchToNextKeyboard() {
        // Service-level switchToNextInputMethod is API 28+; fall back to the
        // InputMethodManager (needs the IME window token) on older devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
            return
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val token = window?.window?.decorView?.windowToken
        if (token != null && imm.switchToNextInputMethod(token, false)) return
        imm.showInputMethodPicker()
    }

    private fun openVeena() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_VOICE_KEYBOARD, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_OPEN_VOICE_KEYBOARD = "open_voice_keyboard"
    }
}
