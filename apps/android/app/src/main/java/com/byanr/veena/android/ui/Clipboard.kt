package com.byanr.veena.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import java.util.concurrent.Executors

// One thread keeps copies in tap order.
private val clipboardWriter = Executors.newSingleThreadExecutor { task ->
    Thread(task, "clipboard").apply { isDaemon = true }
}

/**
 * Puts [text] on the clipboard without blocking the main thread.
 * setPrimaryClip is a binder call into the clipboard service, and on some
 * phones it waits long enough to trigger an ANR (1.1.1 had one from History).
 */
fun Context.copyText(text: String) {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return
    val clip = ClipData.newPlainText("Veena transcript", text)
    clipboardWriter.execute { clipboard.setPrimaryClip(clip) }
}
