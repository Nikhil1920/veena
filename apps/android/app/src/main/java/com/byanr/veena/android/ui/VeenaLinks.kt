package com.byanr.veena.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.byanr.veena.android.data.Language
import com.byanr.veena.android.data.shareMessage

/** Where Veena lives outside the app: source, issues, store listing, contact. */
object VeenaLinks {
    const val SOURCE = "https://github.com/Nikhil1920/veena"
    const val LICENSE = "$SOURCE/blob/main/LICENSE"
    const val WEBSITE = "https://veena.byanr.com"
    const val PRIVACY = "https://veena.byanr.com/privacy/"
    const val EMAIL = "anr.appcontact@gmail.com"
    const val INDIC_CONFORMER = "https://ai4bharat.iitm.ac.in"
    const val PLAY_PACKAGE = "com.byanr.veena"
    const val PLAY_STORE = "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE"
}

fun Context.appVersionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
        .getOrNull() ?: "unknown"

/** Version and device, so a bug report says where it happened. */
private fun Context.diagnostics(): String =
    "Veena ${appVersionName()} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"

fun Context.openUrl(url: String) {
    startOrToast(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "No app can open this link")
}

/** The Play Store app's listing, or the web listing when the Play Store isn't installed. */
fun Context.openPlayStoreListing() {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${VeenaLinks.PLAY_PACKAGE}"))
        .setPackage("com.android.vending")
    try {
        startActivity(market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openUrl(VeenaLinks.PLAY_STORE)
    }
}

/** A new GitHub issue, prefilled with the version and device. */
fun Context.reportIssueOnGitHub() {
    val body = "**What happened?**\n\n\n**What did you expect?**\n\n\n---\n${diagnostics()}"
    openUrl("${VeenaLinks.SOURCE}/issues/new?body=${Uri.encode(body)}")
}

fun Context.emailFeedback() {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(VeenaLinks.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Veena feedback")
        putExtra(Intent.EXTRA_TEXT, "\n\n---\n${diagnostics()}")
    }
    startOrToast(intent, "No email app found. Write to ${VeenaLinks.EMAIL}")
}

/** Shares Veena with a pitch in [language], the one the sharer dictates in. */
fun Context.shareVeena(language: Language?) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareMessage(language, VeenaLinks.WEBSITE))
    }
    startOrToast(Intent.createChooser(send, "Share Veena"), "No app can share this")
}

private fun Context.startOrToast(intent: Intent, failure: String) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
    }
}
