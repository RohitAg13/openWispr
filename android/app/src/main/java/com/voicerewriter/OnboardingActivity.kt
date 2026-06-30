package com.voicerewriter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding: a guided, mostly-on-device setup mirroring the OpenWispr
 * onboarding design. Steps: welcome → choose path (private/cloud) → microphone →
 * voice model (download) or cloud key → bubble overlay → accessibility (incl. the
 * Android-13 "restricted settings" unlock for sideloaded APKs) → first dictation →
 * optional extras → done.
 *
 * Special-access grants (overlay, accessibility) deliver no result callback,
 * so live state is re-read from [SetupUtils] on every ON_RESUME. Shown once on first
 * launch (gated by [Settings.hasCompletedOnboarding]); re-launchable from Settings.
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OpenWisprTheme { OnboardingScreen(::launchDictation, ::goHome) } }
    }

    private fun launchDictation() {
        startActivity(
            Intent(this, RewriteActivity::class.java)
                .putExtra(RewriteActivity.EXTRA_MODE, Defaults.MODE_DICTATE)
                .putExtra(RewriteActivity.EXTRA_AUTO_RECORD, true),
        )
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    companion object {
        fun intent(ctx: Context): Intent = Intent(ctx, OnboardingActivity::class.java)
    }
}

// On-device voice models offered during onboarding — Parakeet first + recommended,
// matching the app's on-device defaults.
private data class ModelOption(val id: String, val name: String, val meta: String)

private val MODEL_OPTIONS = listOf(
    ModelOption(ParakeetModelManager.MODEL_ID, "Parakeet", "~631 MB · most accurate · recommended"),
    ModelOption("base", "Whisper Base", "~142 MB · balanced"),
    ModelOption("tiny", "Whisper Tiny", "~75 MB · fastest, smallest"),
)

@Composable
private fun OnboardingScreen(onLaunchDictation: () -> Unit, onGoHome: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val ctx: Context = activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var step by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf("private") }

    var micGranted by remember { mutableStateOf(SetupUtils.micGranted(ctx)) }
    var micBlocked by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(SetupUtils.canDrawOverlays(ctx)) }
    var a11yGranted by remember { mutableStateOf(SetupUtils.accessibilityEnabled(ctx)) }

    var model by remember { mutableStateOf(ParakeetModelManager.MODEL_ID) }
    var dl by remember { mutableStateOf(if (OnDeviceStt.isReady(ctx, ParakeetModelManager.MODEL_ID)) "done" else "idle") }
    var dlPct by remember { mutableFloatStateOf(0f) }
    var dlError by remember { mutableStateOf<String?>(null) }

    var cloudKey by remember { mutableStateOf("") }
    var a11yPhase by remember { mutableStateOf("restricted") }

    var notif by remember { mutableStateOf(SetupUtils.notificationsGranted(ctx)) }
    var history by remember { mutableStateOf(DictationHistory.keepHistory(ctx)) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) micBlocked = !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notif = granted
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = SetupUtils.micGranted(ctx)
                overlayGranted = SetupUtils.canDrawOverlays(ctx)
                a11yGranted = SetupUtils.accessibilityEnabled(ctx)
                notif = SetupUtils.notificationsGranted(ctx)
                if (a11yGranted) a11yPhase = "on"
                // keep the model step in sync if a download finished while away
                if (path == "private" && dl != "downloading" && OnDeviceStt.isReady(ctx, model)) dl = "done"
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun next() { step = (step + 1).coerceAtMost(8) }
    fun back() { step = (step - 1).coerceAtLeast(0) }

    fun persistStt() {
        scope.launch(Dispatchers.IO) {
            val repo = SettingsRepository(ctx)
            val s = repo.get()
            val updated = if (path == "private") {
                s.copy(sttProvider = "local", sttModel = model)
            } else {
                s.copy(
                    sttProvider = "groq",
                    sttKey = cloudKey.trim(),
                    sttModel = Defaults.STT_PROVIDERS.getValue("groq").defaultModel,
                )
            }
            repo.save(updated)
        }
    }

    fun startDownload() {
        if (OnDeviceStt.isReady(ctx, model)) { persistStt(); dl = "done"; return }
        dl = "downloading"; dlPct = 0f; dlError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (OnDeviceStt.isParakeet(model)) ParakeetModelManager.download(ctx) { p -> dlPct = p }
                    else WhisperModelManager.download(ctx, model) { p -> dlPct = p }
                }
                persistStt()
                dl = "done"
            } catch (t: Throwable) {
                dlError = t.message ?: "Download failed"
                dl = "error"
            }
        }
    }

    fun finishOnboarding() {
        scope.launch {
            withContext(Dispatchers.IO) {
                SettingsRepository(ctx).setOnboardingComplete(true)
                DictationHistory.setKeepHistory(ctx, history)
            }
            if (overlayGranted) runCatching { SetupUtils.startBubble(ctx) }
            onGoHome()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (step in 1..7) {
                TopBar(
                    progress = step / 8f,
                    canSkip = step == 4 || step == 5 || step == 7,
                    onBack = { back() },
                    onSkip = { if (step == 5 && a11yPhase == "restricted") a11yPhase = "list" else next() },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> WelcomeStep(onNext = { next() })
                    1 -> PathStep(path = path, onPick = { path = it }, onNext = { next() })
                    2 -> MicStep(
                        granted = micGranted, blocked = micBlocked,
                        onAllow = {
                            if (micBlocked) ctx.startActivity(SetupUtils.appInfoIntent(ctx))
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onSkip = { next() }, onNext = { next() },
                    )
                    3 -> if (path == "private") {
                        ModelStep(
                            selected = model, onSelect = { if (dl != "downloading") { model = it; dl = if (OnDeviceStt.isReady(ctx, it)) "done" else "idle" } },
                            dl = dl, dlPct = dlPct, dlError = dlError,
                            onDownload = { startDownload() }, onNext = { next() },
                        )
                    } else {
                        CloudKeyStep(
                            key = cloudKey, onKey = { cloudKey = it },
                            onGetKey = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))) },
                            onSwitchPrivate = { path = "private" },
                            onNext = { persistStt(); next() },
                        )
                    }
                    4 -> OverlayStep(
                        granted = overlayGranted,
                        onOpen = { ctx.startActivity(SetupUtils.overlaySettingsIntent(ctx)) },
                        onConfirm = { overlayGranted = SetupUtils.canDrawOverlays(ctx) },
                        onNext = { next() },
                    )
                    5 -> A11yStep(
                        phase = a11yPhase,
                        onOpenRestricted = { ctx.startActivity(SetupUtils.appInfoIntent(ctx)) },
                        onNotGreyed = { a11yPhase = "list" },
                        onOpenA11y = { ctx.startActivity(SetupUtils.accessibilitySettingsIntent()) },
                        onConfirm = { a11yGranted = SetupUtils.accessibilityEnabled(ctx); if (a11yGranted) a11yPhase = "on" },
                        onSkip = { a11yPhase = "skipped" },
                        onNext = { next() },
                    )
                    6 -> DictateStep(onTry = onLaunchDictation, onNext = { next() })
                    7 -> ExtrasStep(
                        notif = notif, history = history,
                        onNotif = {
                            if (!notif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            else notif = !notif
                        },
                        onHistory = { history = !history },
                        onNext = { next() },
                    )
                    else -> DoneStep(
                        micOn = micGranted,
                        sttOn = if (path == "private") dl == "done" else cloudKey.isNotBlank(),
                        sttLabel = if (path == "private") "On-device model" else "Cloud transcription",
                        bubbleOn = overlayGranted,
                        a11yOn = a11yPhase == "on", a11ySkipped = a11yPhase == "skipped",
                        onStart = { finishOnboarding() },
                        onReplay = { step = 0; a11yPhase = "restricted"; if (dl != "done") dlPct = 0f },
                    )
                }
            }
        }
    }
}

/* ----------------------------------------------------------------------------- */
/* shared pieces                                                                  */
/* ----------------------------------------------------------------------------- */

@Composable
private fun TopBar(progress: Float, canSkip: Boolean, onBack: () -> Unit, onSkip: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(14.dp, 14.dp, 18.dp, 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Filled.ChevronLeft, contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(30.dp).clip(CircleShape).clickable { onBack() }.padding(3.dp),
        )
        Box(
            Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline),
        ) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary),
            )
        }
        if (canSkip) {
            Text(
                "Skip", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onSkip() }.padding(start = 4.dp),
            )
        } else {
            Spacer(Modifier.width(28.dp))
        }
    }
}

@Composable
private fun Cta(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (enabled) cs.primary else cs.outline)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleMedium,
            color = if (enabled) cs.onPrimary else cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubLink(text: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(top = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LogoCircle(diameter: Int) {
    Box(
        Modifier.size(diameter.dp).clip(CircleShape).background(SunsetBrush),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null,
            tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size((diameter * 0.5f).dp),
        )
    }
}

@Composable
private fun SuccessPill(text: String) {
    val green = Color(0xFF3E8E5A)
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFFE7F3EA))
            .border(1.dp, Color(0xFFBFE0C9), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Check, null, tint = green, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = green)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(cs.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(34.dp)) }
}

/** Standard step scaffold: scrollable content area + a fixed bottom action block. */
@Composable
private fun StepScaffold(
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
        Column(Modifier.fillMaxWidth().padding(bottom = 26.dp, top = 8.dp)) { actions() }
    }
}

/* ----------------------------------------------------------------------------- */
/* steps                                                                          */
/* ----------------------------------------------------------------------------- */

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            LogoCircle(118)
            Spacer(Modifier.height(30.dp))
            Text(
                "Talk anywhere.\nKeep everything.", style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold, color = cs.onBackground, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "OpenWispr turns your voice into clean, finished text in any app — and does it all on your phone. No cloud, no account.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Cta("Get started", onClick = onNext)
        Spacer(Modifier.height(18.dp))
        Eyebrow("100% on-device · open source")
    }
}

@Composable
private fun PathStep(path: String, onPick: (String) -> Unit, onNext: () -> Unit) {
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("How should it run?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("You can change this anytime in Settings.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            PathCard(
                selected = path == "private", icon = Icons.Filled.Shield,
                title = "Private & offline", tag = "Recommended", tagPrivate = true,
                body = "Runs a small speech model right on your phone. No API key, nothing uploaded — works on a plane.",
                onClick = { onPick("private") },
            )
            Spacer(Modifier.height(14.dp))
            PathCard(
                selected = path == "cloud", icon = Icons.Filled.Bolt,
                title = "Cloud & fastest", tag = "Bring your own key", tagPrivate = false,
                body = "Top accuracy instantly via a cloud model. Needs a free API key, and audio leaves your device.",
                onClick = { onPick("cloud") },
            )
        },
        actions = { Cta("Continue", onClick = onNext) },
    )
}

@Composable
private fun PathCard(selected: Boolean, icon: ImageVector, title: String, tag: String, tagPrivate: Boolean, body: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.secondaryContainer else cs.surface)
            .border(2.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(16.dp))
            .clickable { onClick() }.padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Text(tag.uppercase(), style = MonoEyebrow, fontSize = 10.sp, color = if (tagPrivate) Color(0xFF3E8E5A) else cs.onSurfaceVariant)
            }
            if (selected) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(cs.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun MicStep(granted: Boolean, blocked: Boolean, onAllow: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(20.dp))
            IconTile(Icons.Filled.Mic)
            Spacer(Modifier.height(24.dp))
            Text("Let OpenWispr hear you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(12.dp))
            Text(
                "The microphone is the one thing it can't work without. Your audio is transcribed on your device and never recorded to a file or uploaded.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            when {
                granted -> SuccessPill("Microphone allowed")
                blocked -> Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFFFBEAE2))
                        .border(1.dp, Color(0xFFE9C3B2), RoundedCornerShape(12.dp)).padding(14.dp),
                ) {
                    Text("Microphone is blocked", style = MaterialTheme.typography.titleSmall, color = Color(0xFFB4502E))
                    Spacer(Modifier.height(4.dp))
                    Text("Android won't ask again. Open App info → Permissions → Microphone and switch it on.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        },
        actions = {
            when {
                granted -> Cta("Continue", onClick = onNext)
                blocked -> Cta("Open settings", onClick = onAllow)
                else -> { Cta("Allow microphone", onClick = onAllow); SubLink("Set up later", onClick = onSkip) }
            }
        },
    )
}

@Composable
private fun ModelStep(
    selected: String, onSelect: (String) -> Unit,
    dl: String, dlPct: Float, dlError: String?,
    onDownload: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("Get a voice model", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("Downloads once, then runs fully offline. Parakeet is the most accurate; pick a smaller one for a quicker start.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            MODEL_OPTIONS.forEach { m ->
                ModelRow(m.name, m.meta, selected == m.id, enabled = dl != "downloading") { onSelect(m.id) }
                Spacer(Modifier.height(11.dp))
            }
            when (dl) {
                "downloading" -> {
                    Spacer(Modifier.height(11.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloading…", style = MaterialTheme.typography.titleSmall, color = cs.onBackground)
                        Text("${(dlPct * 100).toInt()}%", style = MonoEyebrow, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.outline)) {
                        Box(Modifier.fillMaxWidth(dlPct.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(cs.primary))
                    }
                }
                "done" -> { Spacer(Modifier.height(20.dp)); SuccessPill("Model ready · works offline") }
                "error" -> {
                    Spacer(Modifier.height(16.dp))
                    Text(dlError ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4502E))
                }
            }
        },
        actions = {
            when (dl) {
                "done" -> Cta("Continue", onClick = onNext)
                "downloading" -> Cta("Downloading…", enabled = false, onClick = {})
                "error" -> Cta("Try again", onClick = onDownload)
                else -> Cta("Download & continue", onClick = onDownload)
            }
        },
    )
}

@Composable
private fun ModelRow(name: String, meta: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp))
            .background(if (selected) cs.secondaryContainer else cs.surface)
            .border(1.5.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(13.dp))
            .clickable(enabled = enabled) { onClick() }.padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text(meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        if (selected) Box(Modifier.size(11.dp).clip(CircleShape).background(cs.primary))
    }
}

@Composable
private fun CloudKeyStep(key: String, onKey: (String) -> Unit, onGetKey: () -> Unit, onSwitchPrivate: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("Add your Groq key", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("Cloud Whisper (large-v3-turbo) is the fastest, most accurate option. Groq has a free tier.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Eyebrow("API key")
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface)
                    .border(1.5.dp, cs.outline, RoundedCornerShape(12.dp)).padding(14.dp),
            ) {
                BasicTextField(
                    value = key, onValueChange = onKey, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onBackground),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                    decorationBox = { inner ->
                        if (key.isEmpty()) Text("gsk_...", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Text("Where do I get a free key? →", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.clickable { onGetKey() })
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer).padding(14.dp)) {
                Column {
                    Text("Heads up: in this mode your audio is sent to Groq for transcription.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text("Prefer fully private? Switch to on-device", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.clickable { onSwitchPrivate() })
                }
            }
        },
        actions = { Cta("Continue", enabled = key.isNotBlank(), onClick = onNext) },
    )
}

@Composable
private fun OverlayStep(granted: Boolean, onOpen: () -> Unit, onConfirm: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(18.dp))
            Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant)) {
                Box(Modifier.align(Alignment.BottomEnd).padding(20.dp).size(54.dp).clip(CircleShape).background(SunsetBrush), contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null, tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Your tap-to-talk bubble", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(12.dp))
            Text("A small floating button lets you dictate from any app. To show it, Android needs permission to display over other apps.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            if (granted) SuccessPill("Bubble enabled")
        },
        actions = {
            if (granted) Cta("Continue", onClick = onNext)
            else { Cta("Open display settings", onClick = onOpen); SubLink("I've enabled it", onClick = onConfirm) }
        },
    )
}

@Composable
private fun A11yStep(
    phase: String, onOpenRestricted: () -> Unit, onNotGreyed: () -> Unit,
    onOpenA11y: () -> Unit, onConfirm: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (phase) {
        "restricted" -> StepScaffold(
            content = {
                Spacer(Modifier.height(18.dp))
                IconTile(Icons.Filled.Lock)
                Spacer(Modifier.height(22.dp))
                Eyebrow("One-time unlock · sideloaded apps")
                Spacer(Modifier.height(9.dp))
                Text("First, allow restricted settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.height(12.dp))
                Text("Because you installed OpenWispr from GitHub, Android greys out the next switch until you unlock it:", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(14.dp)).padding(vertical = 6.dp)) {
                    NumberedRow(1, "Open App info")
                    NumberedRow(2, "Tap the ⋮ menu (top-right)")
                    NumberedRow(3, "Choose Allow restricted settings")
                }
            },
            actions = { Cta("Open App info", onClick = onOpenRestricted); SubLink("My switch isn't greyed out", onClick = onNotGreyed) },
        )
        "on" -> StepScaffold(
            content = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(CircleShape).background(Color(0xFFE7F3EA)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Check, null, tint = Color(0xFF3E8E5A), modifier = Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("Auto-insert is on", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Spacer(Modifier.height(10.dp))
                    Text("Your text will drop straight into whatever you're typing in.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            },
            actions = { Cta("Continue", onClick = onNext) },
        )
        "skipped" -> StepScaffold(
            content = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No problem — clipboard it is", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Without auto-insert, OpenWispr copies your finished text so you can paste it yourself. You can enable auto-insert anytime in Settings.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            },
            actions = { Cta("Continue", onClick = onNext) },
        )
        else -> StepScaffold( // "list"
            content = {
                Spacer(Modifier.height(10.dp))
                Text("Let it type for you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.height(12.dp))
                Text("OpenWispr uses Accessibility for one job only: pasting your finished text into the app you're in. It never reads your screen for anything else, and nothing leaves your phone.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Text("In the list, find OpenWispr → turn it on → tap Allow on the confirmation.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            },
            actions = { Cta("Open accessibility settings", onClick = onOpenA11y); SubLink("I've turned it on", onClick = onConfirm); SubLink("Skip — I'll paste manually", onClick = onSkip) },
        )
    }
    // confirm-on-resume is handled by the parent ON_RESUME observer flipping phase to "on";
    // the user taps Continue on the "on" screen to advance.
}

@Composable
private fun NumberedRow(n: Int, text: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$n", style = MonoEyebrow, color = cs.primary, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground)
    }
}

@Composable
private fun DictateStep(onTry: () -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(8.dp))
            Text("Try it once", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text("Tap the bubble and read this aloud — watch the clean-up happen.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.primaryContainer).padding(14.dp)) {
                Text("“um so send it to mark, I mean john, tomorrow at 2 period”", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(28.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(SunsetBrush).clickable { onTry() }, contentAlignment = Alignment.Center) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null, tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(48.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Eyebrow("Tap to talk") }
        },
        actions = { Cta("Continue", onClick = onNext) },
    )
}

@Composable
private fun ExtrasStep(
    notif: Boolean, history: Boolean,
    onNotif: () -> Unit, onHistory: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("A few finishing touches", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("All optional — keep what helps, skip the rest.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            ToggleRow(Icons.Filled.Notifications, "Notifications", "“Dictated ✓ / fix a word” after each use", notif, onNotif)
            Spacer(Modifier.height(12.dp))
            ToggleRow(Icons.Filled.Shield, "Keep history on-device", "Powers personalization · never uploaded", history, onHistory)
        },
        actions = { Cta("Continue", onClick = onNext) },
    )
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        MiniSwitch(on)
    }
}

@Composable
private fun MiniSwitch(on: Boolean) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.width(44.dp).height(26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) cs.primary else cs.outline),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.padding(3.dp).size(20.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun DoneStep(
    micOn: Boolean, sttOn: Boolean, sttLabel: String, bubbleOn: Boolean,
    a11yOn: Boolean, a11ySkipped: Boolean, onStart: () -> Unit, onReplay: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            LogoCircle(100)
            Spacer(Modifier.height(24.dp))
            Text("You're all set.", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold, color = cs.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Tap the bubble in any app and start talking. Everything stays on your phone.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(14.dp))) {
                RecapRow("Microphone", if (micOn) "On" else "Later", micOn, false)
                RecapRow(sttLabel, if (sttOn) "On" else "Later", sttOn, false)
                RecapRow("Tap-to-talk bubble", if (bubbleOn) "On" else "Later", bubbleOn, false)
                RecapRow("Auto-insert", if (a11yOn) "On" else if (a11ySkipped) "Clipboard" else "Later", a11yOn, a11ySkipped)
            }
        }
        Cta("Start using OpenWispr", onClick = onStart)
        SubLink("Replay onboarding", onClick = onReplay)
    }
}

@Composable
private fun RecapRow(label: String, status: String, on: Boolean, alt: Boolean) {
    val cs = MaterialTheme.colorScheme
    val green = Color(0xFF3E8E5A)
    Row(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (on || alt) Color(0xFFE7F3EA) else cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = if (on || alt) green else cs.onSurfaceVariant, modifier = Modifier.size(13.dp))
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground, modifier = Modifier.weight(1f))
        Text(status, style = MonoEyebrow, fontSize = 11.sp, color = if (on) green else cs.onSurfaceVariant)
    }
}
