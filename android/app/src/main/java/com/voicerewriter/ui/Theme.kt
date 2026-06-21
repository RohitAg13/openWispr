package com.voicerewriter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * OpenWispr brand theme — "muted sunset" (handoff identity): amber → coral → rose,
 * warm cream surfaces and plum ink. Centralized so every screen shares one identity.
 */

// ---- brand constants (also used for gradients / the bubble) ----
val BrandAmber = Color(0xFFEDB079)
val BrandCoral = Color(0xFFE07B52) // primary
val BrandCoralDeep = Color(0xFFC7623C)
val BrandRose = Color(0xFFC16560)
val BrandPlum = Color(0xFF4A2E27)
val MarkCream = Color(0xFFFCF8F4)

/** The signature muted-sunset gradient (≈140°): amber → coral → rose. */
val SunsetBrush: Brush
    get() = Brush.linearGradient(0f to BrandAmber, 0.52f to BrandCoral, 1f to BrandRose)

private val LightColors = lightColorScheme(
    primary = BrandCoral,
    onPrimary = MarkCream,
    primaryContainer = Color(0xFFF3E6D6),       // tint_panel
    onPrimaryContainer = BrandPlum,
    secondary = BrandRose,
    onSecondary = MarkCream,
    secondaryContainer = Color(0xFFF7E1DC),
    onSecondaryContainer = Color(0xFF5A2520),
    tertiary = BrandAmber,
    onTertiary = BrandPlum,
    background = Color(0xFFF6EFE6),             // cream_bg
    onBackground = Color(0xFF4B3A2E),           // ink
    surface = Color(0xFFFFFDFA),               // card
    onSurface = Color(0xFF4B3A2E),
    surfaceVariant = Color(0xFFF3E6D6),         // tint_panel
    onSurfaceVariant = Color(0xFF7E6B5B),       // ink_soft
    outline = Color(0xFFE7DECF),               // hairline
    outlineVariant = Color(0xFFE7DECF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE68C66),               // lighter coral for contrast on plum
    onPrimary = Color(0xFF3A1F17),
    primaryContainer = Color(0xFF6B3A2A),
    onPrimaryContainer = Color(0xFFF3E6D6),
    secondary = BrandAmber,
    onSecondary = Color(0xFF3A1F17),
    secondaryContainer = Color(0xFF5A3A2A),
    onSecondaryContainer = Color(0xFFF6EFE6),
    tertiary = BrandAmber,
    background = Color(0xFF2E1D18),
    onBackground = Color(0xFFF6EFE6),
    surface = Color(0xFF3A2620),
    onSurface = Color(0xFFF6EFE6),
    surfaceVariant = Color(0xFF4A352C),
    onSurfaceVariant = Color(0xFFD8C4B4),
    outline = Color(0xFF5C463B),
    outlineVariant = Color(0xFF4A352C),
)

@Composable
fun OpenWisprTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = OpenWisprTypography,
        content = content,
    )
}
