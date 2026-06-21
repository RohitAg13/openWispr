package com.voicerewriter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * OpenWispr brand theme — "warm sunset": coral → orange → amber, with a pink accent.
 * Centralized so every screen shares one identity (replaces the ad-hoc
 * light/darkColorScheme() calls that were scattered across activities).
 */

// ---- brand constants (also used for gradients / the bubble) ----
val BrandCoral = Color(0xFFFB7185)
val BrandOrange = Color(0xFFF97316)
val BrandAmber = Color(0xFFFBBF24)
val BrandPink = Color(0xFFEC4899)

/** The signature sunset gradient, used on hero headers and the record button. */
val SunsetBrush: Brush
    get() = Brush.linearGradient(listOf(BrandCoral, BrandOrange, BrandAmber))

val SunsetBrushVivid: Brush
    get() = Brush.linearGradient(listOf(BrandPink, BrandOrange))

private val LightColors = lightColorScheme(
    primary = Color(0xFFEA580C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE3D3),
    onPrimaryContainer = Color(0xFF7A2E00),
    secondary = Color(0xFFDB2777),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9EC),
    onSecondaryContainer = Color(0xFF7A0040),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFFFFBF7),
    onBackground = Color(0xFF1C1714),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1714),
    surfaceVariant = Color(0xFFF5E9E0),
    onSurfaceVariant = Color(0xFF6B5D54),
    outline = Color(0xFFD9C8BC),
    outlineVariant = Color(0xFFEADCCF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF441A00),
    primaryContainer = Color(0xFF7A2E00),
    onPrimaryContainer = Color(0xFFFFE3D3),
    secondary = Color(0xFFF472B6),
    onSecondary = Color(0xFF4A0026),
    secondaryContainer = Color(0xFF7A0040),
    onSecondaryContainer = Color(0xFFFFD9EC),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF1C1714),
    onBackground = Color(0xFFF5EDE6),
    surface = Color(0xFF26201C),
    onSurface = Color(0xFFF5EDE6),
    surfaceVariant = Color(0xFF3A322C),
    onSurfaceVariant = Color(0xFFC9BBB0),
    outline = Color(0xFF5C5048),
    outlineVariant = Color(0xFF453B34),
)

@Composable
fun OpenWisprTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
