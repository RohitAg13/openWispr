package com.voicerewriter

import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding: a guided, on-device-by-default setup mirroring the OpenWispr
 * onboarding design. Steps: welcome → speech model (download) → microphone →
 * auto-insert (accessibility, with the tap-to-talk bubble/overlay permission folded in) →
 * polish showcase → personalization → first dictation → done.
 *
 * On-device is the only path here; cloud transcription stays available later in Settings.
 * Special-access grants (overlay, accessibility) deliver no result callback, so live state
 * is re-read from [SetupUtils] on every ON_RESUME. Shown once on first launch (gated by
 * [Settings.hasCompletedOnboarding]); re-launchable from Settings.
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
    ModelOption("base", "Whisper Base", "~142 MB · more accurate"),
    ModelOption("tiny", "Whisper Tiny", "~75 MB · fast"),
)

// Polish-showcase examples cycled on the "It cleans up as you talk" step.
private data class PolishExample(
    val tab: String, val tag: String, val raw: String,
    val clean: List<String>, val list: Boolean, val note: String,
)

private val POLISH_EXAMPLES = listOf(
    PolishExample(
        "Fillers", "Fillers removed",
        "um so, uh, I think we should, like, just ship it",
        listOf("I think we should just ship it."), false,
        "No more “um,” “uh,” “like,” or false starts.",
    ),
    PolishExample(
        "Punctuation", "Auto-punctuation",
        "hey are you free tomorrow lets grab coffee",
        listOf("Hey, are you free tomorrow? Let’s grab coffee."), false,
        "Commas, periods, question marks and capitals — added for you.",
    ),
    PolishExample(
        "Lists", "Numbered list",
        "first buy milk second call sam third finish the deck",
        listOf("Buy milk", "Call Sam", "Finish the deck"), true,
        "Say “first… second… third…” and it lays out a clean list.",
    ),
    PolishExample(
        "Backtrack", "Backtrack",
        "send it to mark, I mean john, at 2",
        listOf("Send it to John at 2."), false,
        "Correct yourself mid-sentence — OpenWispr keeps only the fix.",
    ),
)

@Composable
private fun OnboardingScreen(onLaunchDictation: () -> Unit, onGoHome: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val ctx: Context = activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var step by remember { mutableIntStateOf(0) }

    var micGranted by remember { mutableStateOf(SetupUtils.micGranted(ctx)) }
    var micBlocked by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(SetupUtils.canDrawOverlays(ctx)) }
    var a11yGranted by remember { mutableStateOf(SetupUtils.accessibilityEnabled(ctx)) }

    var model by remember { mutableStateOf(ParakeetModelManager.MODEL_ID) }
    var advancedOpen by remember { mutableStateOf(false) }
    var dl by remember { mutableStateOf(if (OnDeviceStt.isReady(ctx, ParakeetModelManager.MODEL_ID)) "done" else "idle") }
    var dlPct by remember { mutableFloatStateOf(0f) }
    var dlError by remember { mutableStateOf<String?>(null) }

    var a11yPhase by remember { mutableStateOf("restricted") }
    var showA11yConsent by remember { mutableStateOf(false) }
    var polishTab by remember { mutableIntStateOf(0) }

    // Personalization "set up" status — read from the real on-device stores so returning
    // from an editor reflects what the user actually added (see refreshPersonalization).
    var toneDone by remember { mutableStateOf(false) }
    var styleN by remember { mutableIntStateOf(0) }
    var dictN by remember { mutableIntStateOf(0) }
    var contactsDone by remember { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (!granted) micBlocked = !activity.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO)
    }

    fun refreshPersonalization() {
        scope.launch {
            val tone = withContext(Dispatchers.IO) { AppToneRepository(ctx).overrides().isNotEmpty() }
            val vocab = withContext(Dispatchers.IO) { VocabRepository(ctx).get() }
            val samples = withContext(Dispatchers.IO) {
                CorrectionCorpus.all(ctx).count { it.edited && it.cleaned != it.final }
            }
            toneDone = tone
            dictN = vocab.count { it.source != "contact" }
            contactsDone = vocab.any { it.source == "contact" }
            styleN = samples
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = SetupUtils.micGranted(ctx)
                overlayGranted = SetupUtils.canDrawOverlays(ctx)
                a11yGranted = SetupUtils.accessibilityEnabled(ctx)
                if (a11yGranted && a11yPhase != "skipped") a11yPhase = "on"
                // keep the model step in sync if a download finished while away
                if (dl != "downloading" && OnDeviceStt.isReady(ctx, model)) dl = "done"
                refreshPersonalization()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Auto-cycle the polish-showcase tabs while that step is visible.
    LaunchedEffect(step) {
        if (step == 4) {
            while (true) {
                delay(3400)
                polishTab = (polishTab + 1) % POLISH_EXAMPLES.size
            }
        }
    }

    fun next() { step = (step + 1).coerceAtMost(7) }
    fun back() { step = (step - 1).coerceAtLeast(0) }

    fun persistStt() {
        scope.launch(Dispatchers.IO) {
            val repo = SettingsRepository(ctx)
            repo.save(repo.get().copy(sttProvider = "local", sttModel = model))
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
            withContext(Dispatchers.IO) { SettingsRepository(ctx).setOnboardingComplete(true) }
            if (overlayGranted) runCatching { SetupUtils.startBubble(ctx) }
            onGoHome()
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (step in 1..6) {
                TopBar(
                    progress = step / 7f,
                    canSkip = step == 4 || step == 5,
                    onBack = { back() },
                    onSkip = { next() },
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> WelcomeStep(onNext = { next() })
                    1 -> SetupStep(
                        selected = model,
                        advancedOpen = advancedOpen,
                        onToggleAdvanced = { advancedOpen = !advancedOpen },
                        onSelect = { if (dl != "downloading") { model = it; dl = if (OnDeviceStt.isReady(ctx, it)) "done" else "idle" } },
                        dl = dl, dlPct = dlPct, dlError = dlError,
                        onDownload = { startDownload() }, onNext = { next() },
                    )
                    2 -> MicStep(
                        granted = micGranted, blocked = micBlocked,
                        onAllow = {
                            if (micBlocked) ctx.startActivity(SetupUtils.appInfoIntent(ctx))
                            else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        onSkip = { next() }, onNext = { next() },
                    )
                    3 -> A11yStep(
                        phase = a11yPhase,
                        overlayGranted = overlayGranted,
                        onOpenRestricted = { ctx.startActivity(SetupUtils.appInfoIntent(ctx)) },
                        onNotGreyed = { a11yPhase = "list" },
                        onOpenA11y = { showA11yConsent = true },
                        onConfirm = { a11yGranted = SetupUtils.accessibilityEnabled(ctx); if (a11yGranted) a11yPhase = "on" },
                        onGrantOverlay = { ctx.startActivity(SetupUtils.overlaySettingsIntent(ctx)) },
                        onSkip = { a11yPhase = "skipped" },
                        onNext = { next() },
                    )
                    4 -> PolishStep(tab = polishTab, onTab = { polishTab = it }, onNext = { next() })
                    5 -> PersonalizeStep(
                        toneDone = toneDone, styleN = styleN, dictN = dictN, contactsDone = contactsDone,
                        onTone = { ctx.startActivity(Intent(ctx, AppToneActivity::class.java)) },
                        onStyle = { ctx.startActivity(Intent(ctx, StyleMemoryActivity::class.java)) },
                        onDict = { ctx.startActivity(Intent(ctx, VocabActivity::class.java)) },
                        onContacts = { ctx.startActivity(Intent(ctx, ContactsImportActivity::class.java)) },
                        onNext = { next() },
                    )
                    6 -> DictateStep(onTry = onLaunchDictation, onNext = { next() })
                    else -> DoneStep(
                        micOn = micGranted,
                        sttOn = dl == "done",
                        a11yOn = a11yPhase == "on", a11ySkipped = a11yPhase == "skipped",
                        personalCount = listOf(toneDone, styleN > 0, dictN > 0, contactsDone).count { it },
                        onStart = { finishOnboarding() },
                        onReplay = { step = 0; a11yPhase = "restricted"; advancedOpen = false; if (dl != "done") dlPct = 0f },
                    )
                }
            }
        }
    }

    if (showA11yConsent) {
        AccessibilityConsentDialog(
            onConfirm = {
                showA11yConsent = false
                AccessibilityConsent.record(ctx)
                ctx.startActivity(SetupUtils.accessibilitySettingsIntent())
            },
            onDismiss = { showA11yConsent = false },
        )
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
private fun IconTile(icon: ImageVector, size: Int = 72, corner: Int = 20, iconSize: Int = 34) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape(corner.dp)).background(cs.primaryContainer),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = cs.primary, modifier = Modifier.size(iconSize.dp)) }
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
                "OpenWispr turns your voice into clean, finished text in any app — right on your phone. Setup takes a minute.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        Cta("Get started", onClick = onNext)
        Spacer(Modifier.height(18.dp))
        Eyebrow("100% on-device · open source")
    }
}

@Composable
private fun SetupStep(
    selected: String, advancedOpen: Boolean, onToggleAdvanced: () -> Unit, onSelect: (String) -> Unit,
    dl: String, dlPct: Float, dlError: String?,
    onDownload: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val current = MODEL_OPTIONS.first { it.id == selected }
    val isParakeet = selected == ParakeetModelManager.MODEL_ID
    StepScaffold(
        content = {
            Spacer(Modifier.height(4.dp))
            IconTile(Icons.Filled.VerifiedUser, size = 56, corner = 16, iconSize = 27)
            Spacer(Modifier.height(16.dp))
            Text("Speech stays on your phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("OpenWispr downloads a small speech model once, then works fully offline. No account, nothing uploaded.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            // Recommended (currently-selected) model card
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(cs.secondaryContainer)
                    .border(2.dp, cs.primary, RoundedCornerShape(15.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(SunsetBrush), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Mic, null, tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(current.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Text(current.meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
                Text(
                    (if (isParakeet) "Recommended" else "Selected").uppercase(),
                    style = MonoEyebrow, fontSize = 9.5.sp,
                    color = if (isParakeet) Color(0xFF3E8E5A) else cs.onSurfaceVariant,
                )
            }

            when (dl) {
                "downloading" -> {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Downloading…", style = MaterialTheme.typography.titleSmall, color = cs.onBackground)
                        Text("${(dlPct * 100).toInt()}%", style = MonoEyebrow, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(cs.outline)) {
                        Box(Modifier.fillMaxWidth(dlPct.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(cs.primary))
                    }
                }
                "done" -> { Spacer(Modifier.height(18.dp)); SuccessPill("Ready · works offline") }
                "error" -> {
                    Spacer(Modifier.height(16.dp))
                    Text(dlError ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4502E))
                }
                else -> {
                    // Advanced: choose a different model
                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.clickable { onToggleAdvanced() },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Filled.ChevronRight, null, tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(16.dp).rotate(if (advancedOpen) 90f else 0f),
                        )
                        Text("Choose a different model", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant)
                    }
                    if (advancedOpen) {
                        Spacer(Modifier.height(12.dp))
                        MODEL_OPTIONS.forEach { m ->
                            ModelRow(m.name, m.meta, selected == m.id) { onSelect(m.id) }
                            Spacer(Modifier.height(9.dp))
                        }
                        Text(
                            "Prefer a cloud model for max accuracy? You can add an API key later in Settings.",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
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
private fun ModelRow(name: String, meta: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) cs.secondaryContainer else cs.surface)
            .border(1.5.dp, if (selected) cs.primary else cs.outline, RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text(meta, style = MonoEyebrow, fontSize = 10.5.sp, color = cs.onSurfaceVariant)
        }
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
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
private fun A11yStep(
    phase: String, overlayGranted: Boolean,
    onOpenRestricted: () -> Unit, onNotGreyed: () -> Unit,
    onOpenA11y: () -> Unit, onConfirm: () -> Unit, onGrantOverlay: () -> Unit,
    onSkip: () -> Unit, onNext: () -> Unit,
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
                    if (!overlayGranted) {
                        Spacer(Modifier.height(22.dp))
                        BubblePermissionRow(onAllow = onGrantOverlay)
                    }
                }
            },
            actions = { Cta("Continue", onClick = onNext) },
        )
        "skipped" -> StepScaffold(
            content = {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(74.dp).clip(CircleShape).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ContentPaste, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Text("No problem — clipboard it is", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("Without auto-insert, OpenWispr copies your finished text so you can paste it yourself. You can enable auto-insert anytime in Settings.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (!overlayGranted) {
                        Spacer(Modifier.height(22.dp))
                        BubblePermissionRow(onAllow = onGrantOverlay)
                    }
                }
            },
            actions = { Cta("Continue", onClick = onNext) },
        )
        else -> StepScaffold( // "list"
            content = {
                Spacer(Modifier.height(10.dp))
                Text("Let it type for you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.height(12.dp))
                Text("OpenWispr uses Accessibility for one job only: dropping your finished text into whatever app you're in — and showing the floating tap-to-talk bubble. It never reads your screen, and nothing leaves your phone.", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Text("Find OpenWispr → turn it on → tap Allow on the confirmation.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (!overlayGranted) {
                    Spacer(Modifier.height(18.dp))
                    BubblePermissionRow(onAllow = onGrantOverlay)
                }
            },
            actions = { Cta("Open accessibility settings", onClick = onOpenA11y); SubLink("I've turned it on", onClick = onConfirm); SubLink("Skip — I'll paste manually", onClick = onSkip) },
        )
    }
    // confirm-on-resume is handled by the parent ON_RESUME observer flipping phase to "on";
    // the user taps Continue on the "on" screen to advance.
}

/** Folded-in overlay grant for the tap-to-talk bubble (design has no standalone step). */
@Composable
private fun BubblePermissionRow(onAllow: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(13.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.TouchApp, null, tint = cs.primary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Show the tap-to-talk bubble", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text("Lets you dictate from any app · needs display-over-apps", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Text("Allow", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.clickable { onAllow() })
    }
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
private fun PolishStep(tab: Int, onTab: (Int) -> Unit, onNext: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ex = POLISH_EXAMPLES[tab]
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("It cleans up as you talk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("Speak naturally — OpenWispr does the tidying. Here's what it handles automatically.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                POLISH_EXAMPLES.forEachIndexed { i, e ->
                    val on = i == tab
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (on) cs.primary else cs.surface)
                            .border(1.dp, if (on) cs.primary else cs.outline, RoundedCornerShape(10.dp))
                            .clickable { onTab(i) }.padding(horizontal = 2.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            e.tab, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                            color = if (on) cs.onPrimary else cs.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface).border(1.dp, cs.outline, RoundedCornerShape(16.dp)).padding(20.dp)) {
                Eyebrow("You say")
                Spacer(Modifier.height(9.dp))
                Text("“${ex.raw}”", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                    Text(ex.tag.uppercase(), style = MonoEyebrow, fontSize = 10.sp, color = cs.primary)
                    Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                }
                Eyebrow("OpenWispr writes")
                Spacer(Modifier.height(10.dp))
                if (ex.list) {
                    ex.clean.forEachIndexed { i, line ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary)
                            }
                            Text(line, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                        }
                    }
                } else {
                    Text(ex.clean.first(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(ex.note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        },
        actions = { Cta("Nice — what else?", onClick = onNext) },
    )
}

@Composable
private fun PersonalizeStep(
    toneDone: Boolean, styleN: Int, dictN: Int, contactsDone: Boolean,
    onTone: () -> Unit, onStyle: () -> Unit, onDict: () -> Unit, onContacts: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(6.dp))
            Text("Make it sound like you", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(8.dp))
            Text("Optional — set up what helps. Everything you add stays and learns on-device.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            PersonalizeRow(
                Icons.Filled.ChatBubbleOutline, "App tone",
                "Casual in chat, formal in email — on automatically. Tune it per app.",
                done = toneDone, actionLabel = "Customize", statusLabel = "Tuned", onClick = onTone,
            )
            Spacer(Modifier.height(11.dp))
            PersonalizeRow(
                Icons.Filled.EditNote, "Style memory",
                "Paste a few writing samples so rewrites sound like your voice.",
                done = styleN > 0, actionLabel = "Add samples", statusLabel = "$styleN samples", onClick = onStyle,
            )
            Spacer(Modifier.height(11.dp))
            PersonalizeRow(
                Icons.AutoMirrored.Filled.MenuBook, "Personal dictionary",
                "Add names and custom terms so they're always spelled right.",
                done = dictN > 0, actionLabel = "Add terms", statusLabel = "$dictN terms", onClick = onDict,
            )
            Spacer(Modifier.height(11.dp))
            PersonalizeRow(
                Icons.Filled.Group, "Match contact names",
                "Spells the people you know correctly. Matched on-device — contacts never leave your phone.",
                done = contactsDone, actionLabel = "Import", statusLabel = "Imported", onClick = onContacts,
            )
        },
        actions = { Cta("Continue", onClick = onNext) },
    )
}

@Composable
private fun PersonalizeRow(
    icon: ImageVector, title: String, sub: String,
    done: Boolean, actionLabel: String, statusLabel: String, onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
            .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).clickable { onClick() }.padding(15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        if (done) StatusPill(statusLabel) else ActionPill(actionLabel)
    }
}

/** Green "done" chip with a check — matches the personalization set-up rows. */
@Composable
private fun StatusPill(text: String) {
    val green = Color(0xFF3E8E5A)
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFE7F3EA)).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(Icons.Filled.Check, null, tint = green, modifier = Modifier.size(13.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = green)
    }
}

/** Outlined coral call-to-action chip with a chevron — the not-yet-set-up state. */
@Composable
private fun ActionPill(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).border(1.5.dp, cs.primary, RoundedCornerShape(10.dp)).padding(start = 11.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary)
        Icon(Icons.Filled.ChevronRight, null, tint = cs.primary, modifier = Modifier.size(15.dp))
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
            Text("Tap the bubble and read this aloud — watch Backtrack fix it live.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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
private fun DoneStep(
    micOn: Boolean, sttOn: Boolean,
    a11yOn: Boolean, a11ySkipped: Boolean, personalCount: Int,
    onStart: () -> Unit, onReplay: () -> Unit,
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
                RecapRow("On-device model", if (sttOn) "On" else "Later", sttOn, false)
                RecapRow("Auto-insert", if (a11yOn) "On" else if (a11ySkipped) "Clipboard" else "Later", a11yOn, a11ySkipped)
                RecapRow("Personalization", if (personalCount > 0) "$personalCount set up" else "Later", personalCount > 0, false)
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
