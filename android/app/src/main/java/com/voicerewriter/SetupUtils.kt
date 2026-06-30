package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.ContextCompat

/**
 * Shared helpers for the setup surfaces (onboarding + Settings): permission/state checks
 * and the deep-link intents into the relevant system Settings screens. Special-access grants
 * (overlay, accessibility, restricted-settings) never give a result callback, so
 * callers must re-check state on resume.
 */
object SetupUtils {

    // ---- state checks ----

    fun micGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun notificationsGranted(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        else true

    fun canDrawOverlays(ctx: Context): Boolean = AndroidSettings.canDrawOverlays(ctx)

    fun accessibilityEnabled(ctx: Context): Boolean {
        val expected = "${ctx.packageName}/${OpenWisprAccessibilityService::class.java.name}"
        val enabled = AndroidSettings.Secure.getString(
            ctx.contentResolver,
            AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    // ---- bubble service ----

    fun startBubble(ctx: Context) =
        ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))

    fun stopBubble(ctx: Context) = ctx.stopService(Intent(ctx, BubbleService::class.java))

    // ---- deep-link intents ----

    fun overlaySettingsIntent(ctx: Context) = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${ctx.packageName}"),
    )

    fun accessibilitySettingsIntent() = Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)

    /** App info screen — also where "Allow restricted settings" lives for sideloaded APKs. */
    fun appInfoIntent(ctx: Context) = Intent(
        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${ctx.packageName}"),
    )

    fun appPermissionsIntent(ctx: Context) = appInfoIntent(ctx)

    /**
     * Best-effort deep links to common OEM auto-start / background-permission managers
     * (MIUI, ColorOS/Oppo, Vivo, OnePlus, Samsung). Try each until one resolves; the caller
     * should fall back to [appInfoIntent] if none can be launched.
     */
    fun oemAutoStartIntents(): List<Intent> {
        fun c(pkg: String, cls: String) = Intent().setClassName(pkg, cls)
        return listOf(
            c("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            c("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            c("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            c("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            c("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            c("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            c("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
    }
}
