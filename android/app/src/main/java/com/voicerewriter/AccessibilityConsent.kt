package com.voicerewriter

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Prominent disclosure + affirmative consent for the AccessibilityService, required by Google
 * Play policy (the service is used to insert text, not as a disability tool, so it can't declare
 * isAccessibilityTool). The disclosure must appear in the normal enable flow — before the deep-link
 * into system Accessibility settings — and describe what data the service accesses, how it's used,
 * and require an explicit user action. Shown at every enable entry point (onboarding + Settings).
 */
object AccessibilityConsent {
    private const val PREFS = "a11y_consent"
    private const val KEY = "granted"

    /** Whether the user has previously acknowledged the disclosure. */
    fun granted(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Record affirmative consent when the user proceeds from the disclosure dialog. */
    fun record(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

@Composable
fun AccessibilityConsentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Turn on auto-insert",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = cs.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    "OpenWispr uses Android's Accessibility service for one job: typing your dictated " +
                        "and rewritten text into the field you're focused on.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                DisclosureBullet(
                    "What it accesses",
                    "The text field you're currently focused in — so it can place your text there.",
                )
                Spacer(Modifier.height(10.dp))
                DisclosureBullet(
                    "How it's used",
                    "Only to insert your text. Nothing is collected, stored, logged, or sent off your phone.",
                )
                Spacer(Modifier.height(10.dp))
                DisclosureBullet(
                    "You stay in control",
                    "Turn it off anytime in Android Settings → Accessibility, or skip it and paste manually.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("I understand — open settings") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Not now") }
        },
    )
}

@Composable
private fun DisclosureBullet(heading: String, body: String) {
    val cs = MaterialTheme.colorScheme
    Column {
        Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}
