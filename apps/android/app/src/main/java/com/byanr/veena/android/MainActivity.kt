package com.byanr.veena.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.byanr.veena.android.ui.AppRoot
import com.byanr.veena.android.ui.theme.VeenaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeenaTheme {
                AppRoot()
            }
        }
    }
}
