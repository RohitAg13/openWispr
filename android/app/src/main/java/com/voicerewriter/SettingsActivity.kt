package com.voicerewriter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.MonoEyebrow
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import com.voicerewriter.ui.Wordmark
import kotlinx.coroutines.launch

/**
 * Settings, redesigned to the OpenWispr Settings spec: a sectioned, on-device-first
 * surface — setup status, Voice (engine segment + per-model download manager, Parakeet
 * recommended on top), Cleanup & Polish (smart cleanup + AI-polish level + advanced
 * polish model), Bubble, Privacy, Personalization, Reliability, General. Changes persist
 * immediately. All prior capabilities (cloud STT/LLM providers, voice profile, etc.) are
 * preserved under the relevant sections / Advanced.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            OpenWisprTheme {
                SettingsScreen(repo) { lifecycleScope.launch { it() } }
            }
        }
    }
}

@Composable
private fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }

    // permissions / setup
    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(false) }

    // LLM (rewrite / polish model)
    var provider by remember { mutableStateOf(Defaults.DEFAULT_PROVIDER) }
    var model by remember { mutableStateOf(Defaults.DEFAULT_MODEL) }
    var customEndpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf("") }
    var antiAI by remember { mutableStateOf(true) }
    var temperature by remember { mutableFloatStateOf(Defaults.DEFAULT_TEMPERATURE.toFloat()) }

    // STT
    var sttProvider by remember { mutableStateOf(Defaults.DEFAULT_STT_PROVIDER) }
    var sttEndpoint by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var sttModel by remember { mutableStateOf("") }
    var defaultMode by remember { mutableStateOf(Defaults.MODE_DICTATE) }
    var deterministicCleanup by remember { mutableStateOf(true) }
    var polishLevel by remember { mutableStateOf(PolishLevel.OFF) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var bubbleOnlyOnFields by remember { mutableStateOf(true) }
    var keepHistory by remember { mutableStateOf(DictationHistory.keepHistory(context)) }

    var advancedOpen by remember { mutableStateOf(false) }
    var cloudLlmOpen by remember { mutableStateOf(false) }

    // download tracking (a single in-flight download at a time)
    var dlId by remember { mutableStateOf<String?>(null) }
    var dlProgress by remember { mutableFloatStateOf(0f) }
    var modelsRev by remember { mutableStateOf(0) } // bump to recompute readiness after a download

    val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(context)) { SetupUtils.startBubble(context); bubbleOn = true }
        else Toast.makeText(context, "Permission needed to show the bubble", Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifOn = granted }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> micGranted = granted }
    val a11yLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
    }

    fun enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOn) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (!android.provider.Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
        } else { SetupUtils.startBubble(context); bubbleOn = true }
    }

    LaunchedEffect(Unit) {
        val s = repo.get()
        provider = s.provider; model = s.model; customEndpoint = s.customEndpoint; apiKey = s.apiKey
        voice = s.voice; antiAI = s.antiAI; temperature = s.temperature.toFloat()
        sttProvider = s.sttProvider; sttEndpoint = s.sttEndpoint; sttKey = s.sttKey; sttModel = s.sttModel
        defaultMode = s.defaultMode
        deterministicCleanup = s.deterministicCleanup; polishLevel = s.polishLevel
        vadAutoStop = s.vadAutoStop; bubbleOnlyOnFields = s.bubbleOnlyOnFields
        a11yEnabled = SetupUtils.accessibilityEnabled(context)
        notifOn = SetupUtils.notificationsGranted(context)
        micGranted = SetupUtils.micGranted(context)
        loaded = true
    }
    if (!loaded) return

    fun snapshot() = Settings(
        provider = provider, model = model.trim(), customEndpoint = customEndpoint.trim(),
        apiKey = apiKey.trim(), voice = voice, antiAI = antiAI, temperature = temperature.toDouble(),
        sttProvider = sttProvider, sttEndpoint = sttEndpoint.trim(), sttKey = sttKey.trim(),
        sttModel = sttModel.trim(), defaultMode = defaultMode,
        deterministicCleanup = deterministicCleanup, polishLevel = polishLevel,
        vadAutoStop = vadAutoStop, bubbleOnlyOnFields = bubbleOnlyOnFields,
        hasCompletedOnboarding = true,
    )

    // Persist silently on every change (the design saves immediately).
    fun persist() = launch {
        repo.save(snapshot())
        BubbleService.instance?.refreshGating()
    }

    fun sttModelState(id: String): String = when {
        sttModel == id -> "active"
        dlId == id -> "downloading"
        OnDeviceStt.isReady(context, id) -> "downloaded"
        else -> "idle"
    }
    fun llmModelState(id: String): String = when {
        provider == "local" && model == id -> "active"
        dlId == id -> "downloading"
        LlmModelManager.isReady(context, id) -> "downloaded"
        else -> "idle"
    }

    fun downloadStt(id: String) {
        dlId = id; dlProgress = 0f
        launch {
            try {
                if (OnDeviceStt.isParakeet(id)) ParakeetModelManager.download(context) { p -> dlProgress = p }
                else WhisperModelManager.download(context, id) { p -> dlProgress = p }
                sttModel = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null; Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun downloadLlm(id: String) {
        dlId = id; dlProgress = 0f
        launch {
            try {
                LlmModelManager.download(context, id) { p -> dlProgress = p }
                provider = "local"; model = id; dlId = null; modelsRev++; persist()
            } catch (e: Exception) {
                dlId = null; Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
        HeroHeader()

        Column(
            Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // ---------------- SETUP STATUS ----------------
            val setupComplete = bubbleOn && a11yEnabled && micGranted
            SetupStatusCard(setupComplete) {
                if (!setupComplete) {
                    StatusRow("Microphone", if (micGranted) "On" else "Needed to hear you", micGranted) {
                        if (!micGranted) PillButton("Enable") { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                    StatusRow("Floating bubble", if (bubbleOn) "On" else "Tap-to-talk over any app", bubbleOn) {
                        if (!bubbleOn) PillButton("Enable") { enableBubble() }
                    }
                    StatusRow("Auto-insert", if (a11yEnabled) "On" else "Types text into the field you're in", a11yEnabled) {
                        PillButton(if (a11yEnabled) "Manage" else "Enable") {
                            a11yLauncher.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                }
            }

            // ---------------- VOICE · TRANSCRIPTION ----------------
            Section("Voice · transcription") {
                Card {
                    Padded {
                        Label("Engine")
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = listOf("local" to "On-device", "groq" to "Groq", "openai" to "OpenAI", "custom" to "Custom"),
                            selected = sttProvider,
                            onSelect = {
                                sttProvider = it
                                if (it != "custom" && it != "local") {
                                    val d = Defaults.STT_PROVIDERS[it]?.defaultModel.orEmpty()
                                    if (d.isNotEmpty()) sttModel = d
                                }
                                persist()
                            },
                        )
                    }
                    if (sttProvider == "local") {
                        Divider()
                        Padded {
                            Text("Models download once, then run fully offline.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                            modelsRev // read so this recomposes after a download finishes
                            sttModelOptions().forEach { m ->
                                ModelRow(
                                    name = m.name, meta = m.meta, recommended = m.recommended,
                                    state = sttModelState(m.id), progress = dlProgress,
                                    onGet = { downloadStt(m.id) }, onUse = { sttModel = m.id; persist() },
                                )
                                Spacer(Modifier.height(10.dp))
                            }
                        }
                    } else {
                        Divider()
                        Padded {
                            Label("API key")
                            Spacer(Modifier.height(8.dp))
                            KeyField(sttKey, Defaults.STT_PROVIDERS[sttProvider]?.let { keyPlaceholder(sttProvider) } ?: "key") { sttKey = it; persist() }
                            if (sttProvider == "custom") {
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttEndpoint, "Transcription endpoint URL") { sttEndpoint = it; persist() }
                                Spacer(Modifier.height(10.dp))
                                KeyField(sttModel, "Model id") { sttModel = it; persist() }
                            }
                            Spacer(Modifier.height(10.dp))
                            InfoNote(buildString {
                                append("Audio is sent to ")
                                append(if (sttProvider == "groq") "Groq" else if (sttProvider == "openai") "OpenAI" else "your provider")
                                append(" for transcription.")
                            }) { sttProvider = "local"; persist() }
                        }
                    }
                    Divider()
                    ToggleRow("Auto-stop on pause", "End recording when you stop talking", vadAutoStop) { vadAutoStop = it; persist() }
                }
            }

            // ---------------- CLEANUP & POLISH ----------------
            Section("Cleanup & polish") {
                Card {
                    ToggleRow("Smart cleanup", "Fillers, punctuation, numbers, backtracking — on-device, instant", deterministicCleanup) { deterministicCleanup = it; persist() }
                    Divider()
                    Padded {
                        Text("AI polish", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text("An optional model refines the cleaned text.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Segment(
                            options = PolishLevel.entries.map { it.name.lowercase() to it.label },
                            selected = polishLevel.name.lowercase(),
                            onSelect = { sel -> PolishLevel.entries.firstOrNull { it.name.lowercase() == sel }?.let { polishLevel = it; persist() } },
                        )
                        Spacer(Modifier.height(11.dp))
                        Text(polishLevel.blurb, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text("Polish always keeps your words and meaning — it falls back to the clean text if it strays.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)

                        if (polishLevel != PolishLevel.OFF) {
                            Spacer(Modifier.height(14.dp))
                            DisclosureHeader("Advanced polish model", advancedOpen) { advancedOpen = !advancedOpen }
                            AnimatedVisibility(visible = advancedOpen) {
                                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Label("Polish model")
                                    modelsRev
                                    LlmModelManager.MODELS.forEach { m ->
                                        ModelRow(
                                            name = m.label, meta = m.sizeLabel, recommended = m.recommended,
                                            state = llmModelState(m.id), progress = dlProgress,
                                            onGet = { downloadLlm(m.id) }, onUse = { provider = "local"; model = m.id; persist() },
                                        )
                                    }
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Creativity", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                            Text("%.1f".format(temperature), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Slider(value = temperature, onValueChange = { temperature = it }, onValueChangeFinished = { persist() }, valueRange = 0f..1f)
                                    }
                                    ToggleRowBare("Anti-AI phrasing", antiAI) { antiAI = it; persist() }
                                    DisclosureHeader("Use a cloud model instead", cloudLlmOpen) { cloudLlmOpen = !cloudLlmOpen }
                                    AnimatedVisibility(visible = cloudLlmOpen) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Segment(
                                                options = Defaults.PROVIDERS.values.filter { it.id != "local" }.map { it.id to it.label },
                                                selected = if (provider == "local") "" else provider,
                                                onSelect = { p -> provider = p; Defaults.PROVIDERS[p]?.defaultModel?.takeIf { it.isNotEmpty() }?.let { model = it }; persist() },
                                            )
                                            if (provider != "local") {
                                                if (provider == "custom") KeyField(customEndpoint, "Endpoint URL") { customEndpoint = it; persist() }
                                                KeyField(model, "Model id") { model = it; persist() }
                                                KeyField(apiKey, "API key") { apiKey = it; persist() }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---------------- BUBBLE ----------------
            Section("Bubble") {
                Card {
                    ToggleRow("Show bubble", "The floating tap-to-talk button", bubbleOn) { want ->
                        if (want) enableBubble() else { SetupUtils.stopBubble(context); bubbleOn = false }
                    }
                    Divider()
                    ToggleRow("Only on text fields", "Appear only when you can type", bubbleOnlyOnFields) { bubbleOnlyOnFields = it; persist() }
                }
            }

            // ---------------- PRIVACY ----------------
            Section("Privacy") {
                Card {
                    ToggleRow("Keep history", "Stored on this device · powers personalization", keepHistory) {
                        keepHistory = it; DictationHistory.setKeepHistory(context, it)
                    }
                    Divider()
                    NavRow("Clear all data", danger = true, icon = Icons.Default.Delete) {
                        launch { DictationHistory.all(context).forEach { DictationHistory.delete(context, it.id) } }
                        Toast.makeText(context, "On-device history cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // ---------------- PERSONALIZATION ----------------
            Section("Personalization") {
                Card {
                    NavRow("Personal dictionary", "Names & terms you taught it") { context.startActivity(Intent(context, VocabActivity::class.java)) }
                    Divider()
                    NavRow("Learned from edits", "Corrections it remembered") { context.startActivity(Intent(context, LearnedVocabActivity::class.java)) }
                    Divider()
                    NavRow("Style memory", "On-device examples · never uploaded") { context.startActivity(Intent(context, StyleMemoryActivity::class.java)) }
                    Divider()
                    NavRow("Tone by app", "Email, chat, code, notes") { context.startActivity(Intent(context, AppToneActivity::class.java)) }
                    Divider()
                    NavRow("Import from contacts", "Matched on-device · never uploaded") { context.startActivity(Intent(context, ContactsImportActivity::class.java)) }
                }
            }

            // ---------------- RELIABILITY ----------------
            Section("Reliability") {
                Card {
                    NavRow("Auto-start helper", "For Samsung, Xiaomi, OnePlus, Oppo") {
                        val intent = SetupUtils.oemAutoStartIntents().firstOrNull { it.resolveActivity(context.packageManager) != null }
                            ?: SetupUtils.appInfoIntent(context)
                        runCatching { context.startActivity(intent) }
                    }
                }
            }

            // ---------------- GENERAL ----------------
            Section("General") {
                Card {
                    NavRow("Replay onboarding", "Walk through setup again") { context.startActivity(OnboardingActivity.intent(context)) }
                    Divider()
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Version", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        Text("1.0 · open source", style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/* ------------------------ model option tables ------------------------ */

private data class SttModelOption(val id: String, val name: String, val meta: String, val recommended: Boolean)

private fun sttModelOptions(): List<SttModelOption> = buildList {
    add(SttModelOption(ParakeetModelManager.MODEL_ID, "Parakeet", "${ParakeetModelManager.SIZE_LABEL} · most accurate", true))
    WhisperModelManager.MODELS.forEach { add(SttModelOption(it.id, it.label, it.sizeLabel, false)) }
}

private fun keyPlaceholder(provider: String) = when (provider) {
    "groq" -> "gsk_..."; "openai" -> "sk-..."; else -> "key or endpoint"
}

/* ------------------------ reusable pieces ------------------------ */

@Composable
private fun HeroHeader() {
    Column(
        Modifier.fillMaxWidth().background(SunsetBrush).statusBarsPadding().padding(24.dp, 22.dp, 24.dp, 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_aperture), null, tint = MarkCream, modifier = Modifier.size(40.dp))
            Text("Settings", color = MarkCream, style = Wordmark)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(title.uppercase(), style = MonoEyebrow, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
        content()
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)),
        content = content,
    )
}

@Composable
private fun Padded(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun SetupStatusCard(complete: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (complete) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE7F3EA)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color(0xFF3E8E5A), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(if (complete) "You're all set" else "Finish setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(if (complete) "Microphone · Bubble · Auto-insert all active" else "A couple of quick permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
    }
}

@Composable
private fun StatusRow(title: String, subtitle: String, on: Boolean, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun Segment(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (id, label) ->
            val sel = id == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(id) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModelRow(name: String, meta: String, recommended: Boolean, state: String, progress: Float, onGet: () -> Unit, onUse: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, cs.outline, RoundedCornerShape(12.dp)).padding(13.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                    if (recommended) Badge("Recommended", Color(0xFF3E8E5A), Color(0xFFE7F3EA))
                }
                Text(meta, style = MonoEyebrow, fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
            when (state) {
                "active" -> Badge("Active", Color(0xFF3E8E5A), Color(0xFFE7F3EA))
                "downloaded" -> PillOutline("Use", onUse)
                "downloading" -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp, color = cs.primary)
                else -> PillButton("Get", onGet)
            }
        }
        if (state == "downloading") {
            Spacer(Modifier.height(11.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.outline)) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(cs.primary))
            }
        }
    }
}

@Composable
private fun Badge(text: String, fg: Color, bg: Color) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text.uppercase(), style = MonoEyebrow, fontSize = 10.sp, color = fg)
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ToggleRowBare(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String? = null, danger: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    val color = if (danger) Color(0xFFB4502E) else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(icon ?: Icons.Filled.ChevronRight, null, tint = if (danger) color else MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DisclosureHeader(title: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun InfoNote(text: String, onSwitch: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text("Switch to on-device to keep everything private.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onSwitch() })
    }
}

@Composable
private fun KeyField(value: String, placeholder: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(cs.surface).border(1.5.dp, cs.outline, RoundedCornerShape(11.dp)).padding(13.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                inner()
            },
        )
    }
}

@Composable
private fun PillButton(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.primary).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun PillOutline(label: String, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)).clickable { onClick() }.padding(horizontal = 14.dp, vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    }
}
