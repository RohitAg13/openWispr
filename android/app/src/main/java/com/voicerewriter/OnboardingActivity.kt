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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * First-run onboarding. Six steps: welcome → why the download exists → microphone →
 * auto-insert (accessibility, with the tap-to-talk bubble/overlay permission folded in) →
 * first dictation → done.
 *
 * The shape is dictated by one constraint: the speech model is ~631MB, so unlike a cloud
 * dictation app we cannot let the user succeed before asking for anything. Instead the
 * download starts immediately and the permission steps cover it, so by the time they reach
 * the try-it step the model has usually landed — and if it hasn't, that step shows progress
 * rather than a wall. The download is presented as what it is: the reason nothing has to be
 * uploaded, rather than a toll on the way in.
 *
 * On-device is the only path here; cloud transcription stays available later in Settings, as
 * does personalization (dictionary, contacts, tone), which is deliberately not in this flow.
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

// Onboarding downloads exactly these two on-device models — no engine choice. Anything
// else (Whisper, cloud) stays reachable from Settings for people who go looking.
private const val ONBOARDING_STT_MODEL = ParakeetModelManager.MODEL_ID
private val ONBOARDING_LLM_MODEL = LlmModelManager.DEFAULT_MODEL

// Read aloud at the try-it step. Deliberately full of filler and a mid-sentence correction,
// so the cleanup is visible in the before/after we show the user afterwards.
private const val TRY_IT_SCRIPT = "um so send it to mark, I mean john, tomorrow at 2 period"

// Apps named on the welcome screen. Concrete names beat "works anywhere" — but we name them
// as text rather than drawing real icons, which would need <queries> manifest entries.
private const val WELCOME_APPS = "WhatsApp · Gmail · Slack · Notes · anywhere you type"

private const val LAST_STEP = 5

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

    // Downloads live on the model managers themselves (not this composition), so they keep
    // running across activity/process transitions — e.g. finishing onboarding before either
    // one completes. This screen just observes and, below, kicks them off.
    val dl by ParakeetModelManager.downloadState.collectAsState()
    val dlPct by ParakeetModelManager.downloadProgress.collectAsState()
    val dlError by ParakeetModelManager.downloadError.collectAsState()
    val llmDl by LlmModelManager.downloadState.collectAsState()
    val llmPct by LlmModelManager.downloadProgress.collectAsState()

    // Play Store installs don't hit Android's "restricted settings" lockout that sideloaded
    // (GitHub-sourced) APKs used to — that gate only applies to apps installed outside a
    // trusted app store, so onboarding can go straight to the accessibility list.
    var a11yPhase by remember { mutableStateOf("list") }
    var showA11yConsent by remember { mutableStateOf(false) }
    // Android's accessibility grant reports no result, so the parent ON_RESUME below detects
    // it. This only tracks whether we've sent them out at all, so the manual "I've already
    // turned it on" fallback stays hidden until it could plausibly be needed.
    var sentToA11ySettings by remember { mutableStateOf(false) }

    // The user's own first dictation, shown back to them at the try-it step. `tryBaseline` is
    // the LastDictation value captured when they tapped — anything different afterwards is
    // theirs, which keeps a replayed onboarding from claiming credit for an older dictation.
    var tryBaseline by remember { mutableStateOf<String?>(null) }
    var tryResult by remember { mutableStateOf<String?>(null) }
    var tryRaw by remember { mutableStateOf<String?>(null) }

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

    /**
     * Pull the dictation the user just did back into onboarding. [RewriteActivity] already
     * persists it via [LastDictation] on the way out (and reports RESULT_CANCELED even on
     * success), so reading the file beats any activity-result plumbing. The history entry —
     * when history is on and cleanup actually changed something — additionally gives us the
     * raw transcript, so the polish can be shown on the user's own words.
     */
    fun captureTryResult() {
        val baseline = tryBaseline ?: return
        scope.launch {
            val text = withContext(Dispatchers.IO) { LastDictation.get(ctx) }
            if (text.isBlank() || text == baseline) return@launch
            val raw = withContext(Dispatchers.IO) {
                DictationHistory.all(ctx).firstOrNull()
                    ?.takeIf { it.after == text && it.before.isNotBlank() && it.before.trim() != it.after.trim() }
                    ?.before
            }
            tryResult = text
            tryRaw = raw
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                micGranted = SetupUtils.micGranted(ctx)
                overlayGranted = SetupUtils.canDrawOverlays(ctx)
                a11yGranted = SetupUtils.accessibilityEnabled(ctx)
                if (a11yGranted && a11yPhase != "skipped") a11yPhase = "on"
                refreshPersonalization()
                captureTryResult()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun next() { step = (step + 1).coerceAtMost(LAST_STEP) }
    fun back() { step = (step - 1).coerceAtLeast(0) }

    fun persistStt() {
        scope.launch(Dispatchers.IO) {
            val repo = SettingsRepository(ctx)
            repo.save(repo.get().copy(sttProvider = "local", sttModel = ONBOARDING_STT_MODEL))
        }
    }

    // Downloads start the moment onboarding opens — no "download" tap needed. Speech goes
    // first and alone: it's the only model the first dictation needs, and making it share
    // bandwidth with the polish model just pushes back the moment the user can actually talk.
    LaunchedEffect(Unit) { ParakeetModelManager.ensureDownloading(ctx) }
    LaunchedEffect(dl) {
        if (dl == "done") persistStt()
        // Failed speech shouldn't strand polish — start it either way, just not first.
        if (dl == "done" || dl == "error") LlmModelManager.ensureDownloading(ctx, ONBOARDING_LLM_MODEL)
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
            if (step in 1 until LAST_STEP) {
                TopBar(progress = step / LAST_STEP.toFloat(), onBack = { back() })
            }
            // Steps past the download step keep an ambient status, so a silent background
            // download doesn't read as nothing happening. Never a blocker — just a signal.
            if (step in 2 until LAST_STEP && (dl == "downloading" || llmDl == "downloading")) {
                val speechDownloading = dl == "downloading"
                DownloadStatusChip(
                    pct = if (speechDownloading) dlPct else llmPct,
                    label = if (speechDownloading) "Speech model" else "Polish model",
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    0 -> WelcomeStep(onNext = { next() })
                    1 -> PrivacyStep(
                        dl = dl, dlPct = dlPct, dlError = dlError, llmDl = llmDl, llmPct = llmPct,
                        onRetry = { ParakeetModelManager.ensureDownloading(ctx) }, onNext = { next() },
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
                        canConfirmManually = sentToA11ySettings,
                        onOpenA11y = { showA11yConsent = true },
                        onConfirm = { a11yGranted = SetupUtils.accessibilityEnabled(ctx); if (a11yGranted) a11yPhase = "on" },
                        onGrantOverlay = { ctx.startActivity(SetupUtils.overlaySettingsIntent(ctx)) },
                        onSkip = { a11yPhase = "skipped" },
                        onNext = { next() },
                    )
                    4 -> TryItStep(
                        micGranted = micGranted,
                        modelReady = dl == "done", dlPct = dlPct,
                        result = tryResult, raw = tryRaw,
                        onTry = {
                            tryBaseline = LastDictation.get(ctx)
                            onLaunchDictation()
                        },
                        onNext = { next() },
                    )
                    else -> DoneStep(
                        micOn = micGranted,
                        sttOn = dl == "done",
                        a11yOn = a11yPhase == "on", a11ySkipped = a11yPhase == "skipped",
                        personalCount = listOf(toneDone, styleN > 0, dictN > 0, contactsDone).count { it },
                        onPersonalize = { ctx.startActivity(Intent(ctx, VocabActivity::class.java)) },
                        onStart = { finishOnboarding() },
                        onReplay = { step = 0; a11yPhase = "list"; tryResult = null; tryRaw = null },
                    )
                }
            }
        }
    }

    if (showA11yConsent) {
        AccessibilityConsentDialog(
            onConfirm = {
                showA11yConsent = false
                sentToA11ySettings = true
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
private fun TopBar(progress: Float, onBack: () -> Unit) {
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
        // No "Skip" here: every step's own actions already include a forward path (the CTA
        // always advances, and the two askable permissions carry their own opt-out link), so
        // a second skip affordance up here was just one more thing to read.
        Spacer(Modifier.width(28.dp))
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

/** Ambient download status for the steps after the download step. Deliberately phrased as
 *  something arriving rather than something the user is waiting on. */
@Composable
private fun DownloadStatusChip(pct: Float, label: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(cs.primary))
        Text(
            "$label arriving · ${(pct * 100).toInt()}% · keep going",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
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
                "OpenWispr turns your voice into clean, finished text, right on your phone. Setup takes a minute.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                WELCOME_APPS,
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Cta("Get started", onClick = onNext)
        Spacer(Modifier.height(18.dp))
        Eyebrow("100% on-device · open source")
    }
}

/**
 * The download step, framed as what it actually is. A ~1GB download is the most obvious cost
 * of being on-device — but it's also the only *tangible* proof of the privacy claim: a cloud
 * dictation app has nothing to download because your voice goes to its servers instead. So
 * this step leads with why the download exists rather than apologising for it, and shows one
 * progress bar (speech) instead of two — the polish model isn't what gates the first
 * dictation, and a second bar is just a second thing to worry about.
 */
@Composable
private fun PrivacyStep(
    dl: String, dlPct: Float, dlError: String?,
    llmDl: String, llmPct: Float,
    onRetry: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(4.dp))
            IconTile(Icons.Filled.VerifiedUser, size = 56, corner = 16, iconSize = 27)
            Spacer(Modifier.height(16.dp))
            Text("Your voice never leaves this phone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(Modifier.height(10.dp))
            Text(
                "Most dictation apps send your voice to a server to turn it into text. OpenWispr doesn't, which means the speech model has to live here, on your phone. That's what's downloading now: about a minute on wifi, once, and never again.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            ModelCard(
                icon = Icons.Filled.Mic, name = "Speech model",
                meta = "631 MB · one time",
                state = dl, pct = dlPct,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (llmDl == "downloading")
                    "A second, smaller model for polish is landing now (397 MB). It strips filler words and adds punctuation."
                else
                    "A second, smaller model for polish (397 MB) follows after. You can start dictating before it arrives.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )

            if (dl == "error") {
                Spacer(Modifier.height(16.dp))
                Text(dlError ?: "Download failed", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4502E))
            }
            Spacer(Modifier.height(20.dp))
            // Concrete, and testable by the user later — which is what makes it land as proof
            // rather than as marketing. A cloud app cannot make this claim.
            Eyebrow("Then it works in airplane mode · no account · nothing uploaded")
        },
        actions = {
            // The button always just moves on. Making someone sit and watch a ~1GB download
            // reads as stuck, not as progress, and it was the single biggest place onboarding
            // lost people; the download keeps running regardless of where they are.
            when {
                dl == "error" -> Cta("Try again", onClick = onRetry)
                dl == "done" -> Cta("Continue", onClick = onNext)
                else -> Cta("Continue while it downloads", onClick = onNext)
            }
        },
    )
}

/** An on-device model with its own inline progress once downloading. */
@Composable
private fun ModelCard(icon: ImageVector, name: String, meta: String, state: String, pct: Float) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(cs.secondaryContainer)
            .border(1.5.dp, if (state == "done") Color(0xFFBFE0C9) else cs.primary, RoundedCornerShape(15.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(SunsetBrush), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Text(meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            Text(
                when (state) {
                    "done" -> "READY"
                    "downloading" -> "${(pct * 100).toInt()}%"
                    "error" -> "FAILED"
                    else -> "STARTING"
                },
                style = MonoEyebrow, fontSize = 9.5.sp,
                color = if (state == "done") Color(0xFF3E8E5A) else cs.onSurfaceVariant,
            )
        }
        if (state == "downloading") {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline)) {
                Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
            }
        }
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
                "This is the one thing it genuinely can't work without. Your audio is turned into text by the model you just downloaded. Never saved to a file, never uploaded.",
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
    phase: String, overlayGranted: Boolean, canConfirmManually: Boolean,
    onOpenA11y: () -> Unit, onConfirm: () -> Unit, onGrantOverlay: () -> Unit,
    onSkip: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (phase) {
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
                    Text("No problem, clipboard it is", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center)
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
                Spacer(Modifier.height(10.dp))
                Text(
                    "Android calls this Accessibility, which sounds far broader than what we use it for. Precisely:",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                // Naming what it will never do reassures more than any amount of what it will.
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                        .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).padding(15.dp),
                ) {
                    Eyebrow("What it does")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Puts your finished text into whatever app you're in, and shows the tap-to-talk bubble.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onBackground,
                    )
                    Spacer(Modifier.height(14.dp))
                    Eyebrow("What it never does")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Read your screen, log what you type, or send anything anywhere.",
                        style = MaterialTheme.typography.bodyMedium, color = cs.onBackground,
                    )
                }

                Spacer(Modifier.height(22.dp))
                Text("Here's exactly what you'll see:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                Spacer(Modifier.height(12.dp))
                NumberedStep(1, "Find OpenWispr in the list") {
                    MockSettingsRow(label = "OpenWispr", subtitle = "Downloaded app", on = false)
                }
                Spacer(Modifier.height(12.dp))
                NumberedStep(2, "Turn its switch on") {
                    MockSettingsRow(label = "OpenWispr", subtitle = "Downloaded app", on = true)
                }
                Spacer(Modifier.height(12.dp))
                // The system confirmation is where people bail — it's worded to sound alarming
                // and gives no hint that every accessibility app triggers the identical dialog.
                NumberedStep(3, "Android shows a scary-sounding confirmation. That's normal: every app with this permission gets the same one. Tap Allow.")

                if (!overlayGranted) {
                    Spacer(Modifier.height(20.dp))
                    BubblePermissionRow(onAllow = onGrantOverlay)
                }

                Spacer(Modifier.height(18.dp))
                Text(
                    "Rather not? OpenWispr copies your finished text instead and you paste it yourself. Everything else works the same.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
            },
            actions = {
                Cta("Open Accessibility settings", onClick = onOpenA11y)
                // The parent's ON_RESUME observer normally detects the grant on its own, so
                // this manual fallback only earns its place once they've actually been out.
                if (canConfirmManually) SubLink("I've already turned it on", onClick = onConfirm)
                SubLink("Skip, I'll paste manually", onClick = onSkip)
            },
        )
    }
    // confirm-on-resume is handled by the parent ON_RESUME observer flipping phase to "on";
    // the user taps Continue on the "on" screen to advance.
}

/** A numbered instruction, optionally illustrated by a mock of the row they're hunting for. */
@Composable
private fun NumberedStep(n: Int, text: String, illustration: (@Composable () -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
        Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = cs.onBackground)
            if (illustration != null) {
                Spacer(Modifier.height(9.dp))
                illustration()
            }
        }
    }
}

/**
 * A mock of the Android settings row the user is about to go hunting for. Drawn rather than
 * screenshotted so it stays correct across themes and OS versions — the point is to make the
 * target recognisable at a glance, since this hand-off into system settings is where an
 * Android dictation app loses the most people.
 */
@Composable
private fun MockSettingsRow(label: String, subtitle: String, on: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surface)
            .border(1.5.dp, cs.primary, RoundedCornerShape(12.dp)).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(SunsetBrush), contentAlignment = Alignment.Center) {
            Icon(
                androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null,
                tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        // Fake switch, matching the platform's shape closely enough to be recognisable.
        Box(
            Modifier.size(width = 40.dp, height = 23.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) cs.primary else cs.outline),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 3.dp).size(17.dp).clip(CircleShape).background(com.voicerewriter.ui.MarkCream))
        }
    }
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
            // Pre-empts the usual floating-overlay objection: people assume it parks itself
            // on screen forever.
            Text("Appears when there's a text field to type into, and goes away when you're done.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Text("Allow", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.clickable { onAllow() })
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

/**
 * The payoff. Everything before this was the user spending trust; this is the first moment
 * they get something back, so it has to visibly land — the previous version bounced them to
 * [RewriteActivity] and returned them to an identical screen, which reads as nothing having
 * happened. Now their own sentence comes back, with the cleanup shown on their words rather
 * than on canned examples.
 */
@Composable
private fun TryItStep(
    micGranted: Boolean, modelReady: Boolean, dlPct: Float,
    result: String?, raw: String?,
    onTry: () -> Unit, onNext: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    StepScaffold(
        content = {
            Spacer(Modifier.height(8.dp))
            if (result == null) {
                Text("Try it once", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap the circle and read this aloud, mistakes and all. The filler and the mid-sentence correction are the point.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(cs.primaryContainer).padding(14.dp)) {
                    Text("“$TRY_IT_SCRIPT”", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape).background(SunsetBrush)
                            .clickable(enabled = modelReady && micGranted) { onTry() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null, tint = com.voicerewriter.ui.MarkCream, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        // The wait framed as an arrival with a number on it, never a dead end —
                        // and Continue below stays live throughout.
                        !modelReady -> Eyebrow("Speech model ${(dlPct * 100).toInt()}% · almost there")
                        !micGranted -> Eyebrow("Needs the microphone · go back a step")
                        else -> Eyebrow("Tap to talk")
                    }
                }
            } else {
                Text("That was all on your phone.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = cs.onBackground, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "No account, no upload, no server. Here's what you just said:",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(20.dp))
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface)
                        .border(1.dp, cs.outline, RoundedCornerShape(16.dp)).padding(18.dp),
                ) {
                    // Only when cleanup actually changed something — otherwise the "before"
                    // is just the answer twice, which undersells rather than demonstrates.
                    if (raw != null) {
                        Eyebrow("You said")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "“$raw”", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                            Text("CLEANED UP", style = MonoEyebrow, fontSize = 10.sp, color = cs.primary)
                            Box(Modifier.weight(1f).height(1.dp).background(cs.outline))
                        }
                    }
                    Eyebrow("OpenWispr wrote")
                    Spacer(Modifier.height(9.dp))
                    Text(result, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                }
                Spacer(Modifier.height(18.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SuccessPill("Nice, that worked") }
            }
        },
        actions = {
            if (result == null) Cta("Skip for now", onClick = onNext)
            else Cta("Continue", onClick = onNext)
        },
    )
}

@Composable
private fun DoneStep(
    micOn: Boolean, sttOn: Boolean,
    a11yOn: Boolean, a11ySkipped: Boolean, personalCount: Int,
    onPersonalize: () -> Unit, onStart: () -> Unit, onReplay: () -> Unit,
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
                RecapRow("Speech model", if (sttOn) "On" else "Downloading", sttOn, false)
                RecapRow("Auto-insert", if (a11yOn) "On" else if (a11ySkipped) "Clipboard" else "Later", a11yOn, a11ySkipped)
            }
            Spacer(Modifier.height(12.dp))
            // Personalization used to be a whole step of four rows, each launching its own
            // editor — a maze in the middle of onboarding, for something nobody needs before
            // their first dictation. It lives in Settings; this is the signpost to it.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                    .border(1.dp, cs.outline, RoundedCornerShape(14.dp)).clickable { onPersonalize() }.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Teach it your words", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    Text(
                        "Names, jargon, contacts, tone per app. All learned on-device.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                ActionPill(if (personalCount > 0) "$personalCount set up" else "Set up")
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Every permission here can be changed later in Settings.",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
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
