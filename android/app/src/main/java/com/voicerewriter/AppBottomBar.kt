package com.voicerewriter

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.Mulish
import com.voicerewriter.ui.SunsetBrush

/** Which destination the bar is currently showing. */
enum class NavTab { HOME, SETTINGS }

/**
 * The app's persistent bottom navigation: Home · Talk · Settings.
 *
 * Shared between [HomeActivity] and [SettingsActivity] because it used to live only in Home,
 * which meant tapping "Settings" started a separate Activity with no bar — the nav bar
 * vanished on one of its own destinations, and the only way back was the system back button.
 * Reported by email on a Galaxy S21 (2026-11) and correct: a bottom nav that disappears when
 * you use it is not a bottom nav.
 *
 * Each host passes what its own tabs should do. Settings sends Home to `finish()` rather than
 * starting a new [HomeActivity], since Home is already underneath it on the back stack and
 * launching a second copy would make back go somewhere surprising.
 */
@Composable
fun AppBottomBar(
    selected: NavTab,
    onHome: () -> Unit,
    onTalk: () -> Unit,
    onSettings: () -> Unit,
) {
    // Light tap feedback so the bar feels physical. VIRTUAL_KEY is the standard "button press"
    // haptic and respects the system's touch-feedback setting.
    val view = LocalView.current
    fun haptic() = view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    // Plain Box (not Surface) so the raised Talk FAB can overflow above the bar without being clipped.
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(14.dp, 8.dp, 14.dp, 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavItem(
                label = "Home",
                selected = selected == NavTab.HOME,
                onClick = { haptic(); if (selected != NavTab.HOME) onHome() },
            ) {
                Icon(
                    painterResource(R.drawable.ic_aperture), null,
                    tint = if (selected == NavTab.HOME) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            // Talk (raised FAB)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.offset(y = (-18).dp).size(58.dp).clip(CircleShape).background(SunsetBrush)
                        .clickable { haptic(); onTalk() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_aperture), null, tint = MarkCream,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    "Talk", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = (-12).dp),
                )
            }
            NavItem(
                label = "Settings",
                selected = selected == NavTab.SETTINGS,
                onClick = { haptic(); if (selected != NavTab.SETTINGS) onSettings() },
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings_lines), null,
                    tint = if (selected == NavTab.SETTINGS) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.width(88.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Text(
            label, fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
