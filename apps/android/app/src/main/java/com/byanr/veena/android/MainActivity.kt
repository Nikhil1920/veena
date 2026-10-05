package com.byanr.veena.android

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
        // Deep link from the IME (missing gate: permission, model...): land the
        // app on the voice-keyboard setup screen exactly once per launch. The
        // extra is stripped so recreation doesn't re-trigger the navigation.
        if (intent.getBooleanExtra(EXTRA_OPEN_VOICE_KEYBOARD, false)) {
            intent.removeExtra(EXTRA_OPEN_VOICE_KEYBOARD)
            ViewModelProvider(this)[AppViewModel::class.java].requestVoiceKeyboard()
        }
        setContent {
            VeenaTheme {
                AppRoot()
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_VOICE_KEYBOARD = "open_voice_keyboard"
    }
}
