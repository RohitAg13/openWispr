package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.net.URLEncoder

/**
 * Ways to reach us from inside the app. Until now there were none: no email, no issue link,
 * nothing in Settings, so the only feedback channel was a Play Store review that we can reply
 * to but not ask questions in.
 *
 * Both routes prefill the boring diagnostic details, because "which version, which phone" is the
 * first thing every report needs and the last thing anyone remembers to include. Nothing is sent
 * anywhere by the app itself: these open the user's mail client or a browser with the text
 * already filled in, and the user reads it and presses send. That matters for an app whose whole
 * pitch is that it doesn't phone home.
 */
object Feedback {

    const val EMAIL = "madudeyo@gmail.com"
    const val REPO = "https://github.com/RohitAg13/openWispr"

    /**
     * Version, phone and OS. Deliberately nothing that identifies a person: no install id, no
     * account, no dictation content.
     */
    fun diagnostics(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        return buildString {
            append("App: OpenWispr ").append(version).append('\n')
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        }
    }

    /**
     * Mail client, prefilled. `mailto:` with ACTION_SENDTO rather than ACTION_SEND so only mail
     * apps match - ACTION_SEND would offer the whole share sheet, which is not what "email us"
     * means.
     */
    fun emailIntent(context: Context): Intent {
        val body = "\n\n---\n" + diagnostics(context) + "\n"
        val uri = "mailto:$EMAIL" +
            "?subject=" + enc("OpenWispr feedback") +
            "&body=" + enc(body)
        return Intent(Intent.ACTION_SENDTO, Uri.parse(uri))
    }

    /** GitHub's new-issue form, prefilled with a template and the diagnostics. */
    fun issueIntent(context: Context): Intent {
        val body = buildString {
            append("**What happened?**\n\n\n")
            append("**What did you expect?**\n\n\n")
            append("**Steps to reproduce**\n\n\n")
            append("---\n")
            append(diagnostics(context))
            append('\n')
        }
        val url = "$REPO/issues/new?title=" + enc("") + "&body=" + enc(body)
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    fun playListingIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${context.packageName}"))

    /**
     * URLEncoder is form encoding, which turns a space into "+". That is correct in a query
     * string but wrong in a mailto body, where "+" stays a literal plus. Fix it up to %20,
     * which is valid in both.
     */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
