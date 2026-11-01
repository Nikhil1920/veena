package com.byanr.veena.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.byanr.veena.android.ui.AppRoot
import com.byanr.veena.android.ui.theme.VeenaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleVoiceKeyboardLink(intent)
        setContent {
            VeenaTheme {
                AppRoot()
            }
        }
    }

    // The IME launches with CLEAR_TOP | SINGLE_TOP, so a running app gets the
    // deep link here instead of in onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVoiceKeyboardLink(intent)
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        // The voice keyboard shares the engine and may have unloaded it or
        // switched its language; reload the app's language (no-op when current).
        ViewModelProvider(this)[AppViewModel::class.java].warmEngine()
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
    }

    /**
     * Deep link from the IME (missing gate: permission, model...): land the
     * app on the voice-keyboard setup screen exactly once. The extra is
     * stripped so recreation doesn't re-trigger the navigation.
     */
    private fun handleVoiceKeyboardLink(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE_KEYBOARD, false)) {
            intent.removeExtra(EXTRA_OPEN_VOICE_KEYBOARD)
            ViewModelProvider(this)[AppViewModel::class.java].requestVoiceKeyboard()
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE_KEYBOARD = "open_voice_keyboard"

        /** True while the app's UI is on screen; the IME keeps the shared engine loaded then. */
        @Volatile
        var isInForeground = false
            private set
    }
}
