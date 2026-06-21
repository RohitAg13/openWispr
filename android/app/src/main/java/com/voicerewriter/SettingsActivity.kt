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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            OpenWisprTheme {
                SettingsScreen(repo) { lifecycleScope.launch { it() } }
            }
        }
    }
}

private fun startBubble(ctx: Context) =
    ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))

private fun stopBubble(ctx: Context) =
    ctx.stopService(Intent(ctx, BubbleService::class.java))

private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val expected = "${ctx.packageName}/${OpenWisprAccessibilityService::class.java.name}"
    val enabled = android.provider.Settings.Secure.getString(
        ctx.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun notificationsEnabled(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    else true

private fun micEnabled(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }

    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var notifOn by remember { mutableStateOf(true) }
    var micGranted by remember { mutableStateOf(false) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(context)) { startBubble(context); bubbleOn = true }
        else Toast.makeText(context, "Permission needed to show the bubble", Toast.LENGTH_SHORT).show()
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifOn = granted }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }
    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { a11yEnabled = isAccessibilityEnabled(context) }

    fun enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifOn) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(
                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        } else { startBubble(context); bubbleOn = true }
    }

    // LLM provider
    var provider by remember { mutableStateOf(Defaults.DEFAULT_PROVIDER) }
    var model by remember { mutableStateOf(Defaults.DEFAULT_MODEL) }
    var customEndpoint by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf("") }
    var antiAI by remember { mutableStateOf(true) }
    var temperature by remember { mutableStateOf(Defaults.DEFAULT_TEMPERATURE.toFloat()) }
    var providerMenu by remember { mutableStateOf(false) }
    var llmModelMenu by remember { mutableStateOf(false) }
    var llmReady by remember { mutableStateOf(false) }
    var llmProgress by remember { mutableStateOf<Float?>(null) }

    // Speech-to-text
    var sttProvider by remember { mutableStateOf(Defaults.DEFAULT_STT_PROVIDER) }
    var sttEndpoint by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var sttModel by remember { mutableStateOf("") }
    var defaultMode by remember { mutableStateOf(Defaults.MODE_DICTATE) }
    var deterministicCleanup by remember { mutableStateOf(true) }
    var cleanupDictation by remember { mutableStateOf(true) }
    var localLlmPolish by remember { mutableStateOf(false) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var bubbleOnlyOnFields by remember { mutableStateOf(true) }
    var sttProviderMenu by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableStateOf<Float?>(null) }
    var sttModelMenu by remember { mutableStateOf(false) }

    var advancedOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val s = repo.get()
        provider = s.provider; model = s.model; customEndpoint = s.customEndpoint; apiKey = s.apiKey
        voice = s.voice; antiAI = s.antiAI; temperature = s.temperature.toFloat()
        sttProvider = s.sttProvider; sttEndpoint = s.sttEndpoint; sttKey = s.sttKey; sttModel = s.sttModel
        defaultMode = s.defaultMode
        deterministicCleanup = s.deterministicCleanup; cleanupDictation = s.cleanupDictation
        localLlmPolish = s.localLlmPolish; vadAutoStop = s.vadAutoStop
        bubbleOnlyOnFields = s.bubbleOnlyOnFields
        a11yEnabled = isAccessibilityEnabled(context)
        notifOn = notificationsEnabled(context)
        micGranted = micEnabled(context)
        loaded = true
    }
    LaunchedEffect(provider, model) {
        llmReady = provider == "local" && LlmModelManager.isReady(context, model)
    }
    LaunchedEffect(sttProvider, sttModel) {
        if (sttProvider == "local") {
            if (WhisperModelManager.MODELS.none { it.id == sttModel }) sttModel = WhisperModelManager.DEFAULT_MODEL
            modelReady = WhisperModelManager.isReady(context, sttModel)
        }
    }

    if (!loaded) return

    fun save() = launch {
        repo.save(
            Settings(
                provider = provider, model = model.trim(), customEndpoint = customEndpoint.trim(),
                apiKey = apiKey.trim(), voice = voice, antiAI = antiAI, temperature = temperature.toDouble(),
                sttProvider = sttProvider, sttEndpoint = sttEndpoint.trim(), sttKey = sttKey.trim(),
                sttModel = sttModel.trim(), defaultMode = defaultMode,
                deterministicCleanup = deterministicCleanup, cleanupDictation = cleanupDictation,
                localLlmPolish = localLlmPolish, vadAutoStop = vadAutoStop,
                bubbleOnlyOnFields = bubbleOnlyOnFields,
            )
        )
        // Apply the field-gating preference to a running bubble immediately.
        BubbleService.instance?.refreshGating()
        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        HeroHeader()

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- Setup / permissions ----
            val setupComplete = bubbleOn && a11yEnabled && micGranted
            SectionCard(if (setupComplete) "You're all set" else "Finish setup", highlight = !setupComplete) {
                if (setupComplete) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "Everything's ready — tap the bubble on any text field and speak.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    StatusRow(
                        title = "Floating bubble",
                        subtitle = if (bubbleOn) "On — floats over other apps so you can dictate anywhere."
                        else "Floats over other apps so you can dictate anywhere.",
                        on = bubbleOn,
                    ) {
                        Switch(checked = bubbleOn, onCheckedChange = { want ->
                            if (want) enableBubble() else { stopBubble(context); bubbleOn = false }
                        })
                    }
                    StatusRow(
                        title = "Microphone",
                        subtitle = if (micGranted) "On — lets OpenWispr hear what you dictate."
                        else "So OpenWispr can hear what you dictate.",
                        on = micGranted,
                    ) {
                        if (micGranted) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        else ActionButton("Enable") { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    }
                    StatusRow(
                        title = "Auto-insert",
                        subtitle = if (a11yEnabled) "On — types the cleaned text into the field you're in."
                        else "Types the cleaned text straight into the field you're in.",
                        on = a11yEnabled,
                    ) {
                        ActionButton(if (a11yEnabled) "Manage" else "Enable") {
                            accessibilityLauncher.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    StatusRow(
                        title = "Notifications",
                        subtitle = if (notifOn) "On — tells you when a name needs a quick fix."
                        else "Tells you when a mis-heard name needs a quick fix.",
                        on = notifOn,
                    ) {
                        if (!notifOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            ActionButton("Enable") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        } else {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // ---- Bubble behavior ----
            SectionCard("Bubble") {
                SwitchRow(
                    "Only show on text fields",
                    "The bubble appears when you tap an editable field and hides otherwise. Needs Auto-insert on.",
                    bubbleOnlyOnFields,
                ) { bubbleOnlyOnFields = it }
            }

            // ---- Voice ----
            SectionCard("Voice") {
                Text(
                    "Where speech is turned into text.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(expanded = sttProviderMenu, onExpandedChange = { sttProviderMenu = it }) {
                    OutlinedTextField(
                        value = Defaults.STT_PROVIDERS[sttProvider]?.label ?: sttProvider,
                        onValueChange = {}, readOnly = true, label = { Text("Speech-to-text") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttProviderMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = sttProviderMenu, onDismissRequest = { sttProviderMenu = false }) {
                        Defaults.STT_PROVIDERS.values.forEach { p ->
                            DropdownMenuItem(text = { Text(p.label) }, onClick = {
                                sttProvider = p.id
                                if (p.id != "custom" && p.defaultModel.isNotEmpty()) sttModel = p.defaultModel
                                sttProviderMenu = false
                            })
                        }
                    }
                }

                if (sttProvider == "local") {
                    ExposedDropdownMenuBox(expanded = sttModelMenu, onExpandedChange = { sttModelMenu = it }) {
                        val sel = WhisperModelManager.model(sttModel)
                        OutlinedTextField(
                            value = "${sel.label} (${sel.sizeLabel})", onValueChange = {}, readOnly = true,
                            label = { Text("Whisper model") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttModelMenu) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(expanded = sttModelMenu, onDismissRequest = { sttModelMenu = false }) {
                            WhisperModelManager.MODELS.forEach { m ->
                                DropdownMenuItem(text = { Text("${m.label} (${m.sizeLabel})") },
                                    onClick = { sttModel = m.id; sttModelMenu = false })
                            }
                        }
                    }
                    DownloadRow(
                        ready = modelReady,
                        progress = modelProgress,
                        readyText = "Ready — runs fully offline on this phone.",
                        notReadyText = "Download once (${WhisperModelManager.model(sttModel).sizeLabel}).",
                    ) {
                        val id = sttModel; modelProgress = 0f
                        launch {
                            try {
                                WhisperModelManager.download(context, id) { p -> modelProgress = p }
                                modelReady = true; modelProgress = null
                                Toast.makeText(context, "Model ready", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                modelProgress = null
                                Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } else {
                    Defaults.STT_PROVIDERS[sttProvider]?.keyHelp?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
                    if (sttProvider == "custom") {
                        OutlinedTextField(sttEndpoint, { sttEndpoint = it }, label = { Text("Transcription endpoint URL") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    OutlinedTextField(sttModel, { sttModel = it }, label = { Text("Model") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(sttKey, { sttKey = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }

                SwitchRow("Auto-stop when you pause", "On-device voice detection ends recording after a short pause.",
                    vadAutoStop) { vadAutoStop = it }
            }

            // ---- Cleanup ----
            SectionCard("Cleanup") {
                SwitchRow(
                    "Smart cleanup (instant)",
                    "Offline rules: remove fillers, fix spoken punctuation/numbers, resolve self-corrections.",
                    deterministicCleanup,
                ) { deterministicCleanup = it }

                val isLocal = provider == "local"
                SwitchRow(
                    "Polish with LLM",
                    if (isLocal) "Also run the on-device model. Off by default — small models tend to over-edit."
                    else "Also run the cloud model for grammar and flow.",
                    if (isLocal) localLlmPolish else cleanupDictation,
                ) { if (isLocal) localLlmPolish = it else cleanupDictation = it }
            }

            // ---- Tone by app ----
            SectionCard("Tone by app") {
                StatusRow(
                    title = "Formal vs casual by app",
                    subtitle = "Polish dictation differently per app — formal in email/docs, casual in chat. Used when “Polish with LLM” is on.",
                    on = false,
                    showDot = false,
                ) {
                    ActionButton("Edit") { context.startActivity(Intent(context, AppToneActivity::class.java)) }
                }
            }

            // ---- Dictionary ----
            SectionCard("Personal dictionary") {
                StatusRow(
                    title = "Names, emails & jargon",
                    subtitle = "Teach OpenWispr words it mishears — corrected on-device.",
                    on = false,
                    showDot = false,
                ) {
                    ActionButton("Manage") { context.startActivity(Intent(context, VocabActivity::class.java)) }
                }
            }

            // ---- Learned words ----
            SectionCard("Learned words") {
                StatusRow(
                    title = "Auto-learned from your edits",
                    subtitle = "Review and undo names OpenWispr picked up when you corrected dictation.",
                    on = false,
                    showDot = false,
                ) {
                    ActionButton("Review") { context.startActivity(Intent(context, LearnedVocabActivity::class.java)) }
                }
            }

            // ---- Advanced ----
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().clickable { advancedOpen = !advancedOpen }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Advanced", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Icon(if (advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
                AnimatedVisibility(visible = advancedOpen) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {

                        Text("Rewrite model (long-press the bubble)", style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(expanded = providerMenu, onExpandedChange = { providerMenu = it }) {
                            OutlinedTextField(
                                value = Defaults.PROVIDERS[provider]?.label ?: provider, onValueChange = {},
                                readOnly = true, label = { Text("Provider") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(expanded = providerMenu, onDismissRequest = { providerMenu = false }) {
                                Defaults.PROVIDERS.values.forEach { p ->
                                    DropdownMenuItem(text = { Text(p.label) }, onClick = {
                                        provider = p.id
                                        if (p.id != "custom" && p.defaultModel.isNotEmpty()) model = p.defaultModel
                                        providerMenu = false
                                    })
                                }
                            }
                        }

                        if (provider == "local") {
                            ExposedDropdownMenuBox(expanded = llmModelMenu, onExpandedChange = { llmModelMenu = it }) {
                                val sel = LlmModelManager.model(model)
                                OutlinedTextField(
                                    value = "${sel.label} (${sel.sizeLabel})", onValueChange = {}, readOnly = true,
                                    label = { Text("On-device model") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = llmModelMenu) },
                                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                                )
                                ExposedDropdownMenu(expanded = llmModelMenu, onDismissRequest = { llmModelMenu = false }) {
                                    LlmModelManager.MODELS.forEach { m ->
                                        DropdownMenuItem(text = { Text("${m.label} (${m.sizeLabel})") },
                                            onClick = { model = m.id; llmModelMenu = false })
                                    }
                                }
                            }
                            DownloadRow(
                                ready = llmReady, progress = llmProgress,
                                readyText = "Ready — runs offline on this phone.",
                                notReadyText = "Download once (${LlmModelManager.model(model).sizeLabel}).",
                            ) {
                                val id = model; llmProgress = 0f
                                launch {
                                    try {
                                        LlmModelManager.download(context, id) { p -> llmProgress = p }
                                        llmReady = true; llmProgress = null
                                        Toast.makeText(context, "Model ready", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        llmProgress = null
                                        Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            Defaults.PROVIDERS[provider]?.keyHelp?.takeIf { it.isNotEmpty() }?.let { Hint(it) }
                            if (provider == "custom") {
                                OutlinedTextField(customEndpoint, { customEndpoint = it }, label = { Text("Endpoint URL") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        }

                        OutlinedTextField(voice, { voice = it }, label = { Text("Voice profile (optional)") },
                            placeholder = { Text("Describe how you write, or paste a few samples.") },
                            minLines = 3, modifier = Modifier.fillMaxWidth())

                        SwitchRow("Anti-AI guardrails", "Ban the obvious LLM tells.", antiAI) { antiAI = it }

                        Column {
                            Text("Creativity: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.bodyMedium)
                            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..1.5f)
                        }
                    }
                }
            }

            Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  Save settings")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------- reusable pieces ----------------

@Composable
private fun HeroHeader() {
    Column(
        modifier = Modifier.fillMaxWidth().background(SunsetBrush).padding(24.dp, 36.dp, 24.dp, 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
        Text("OpenWispr", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Speak. It types — anywhere.", color = Color.White.copy(alpha = 0.92f),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionCard(
    title: String,
    highlight: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun StatusRow(
    title: String,
    subtitle: String,
    on: Boolean,
    showDot: Boolean = true,
    trailing: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (showDot) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(
                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        trailing()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) { Text(label) }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DownloadRow(
    ready: Boolean,
    progress: Float?,
    readyText: String,
    notReadyText: String,
    onDownload: () -> Unit,
) {
    val downloading = progress != null
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                ready -> readyText
                downloading -> "Downloading… ${((progress ?: 0f) * 100).toInt()}%"
                else -> notReadyText
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (!ready) OutlinedButton(enabled = !downloading, onClick = onDownload) { Text(if (downloading) "…" else "Download") }
    }
    if (downloading) LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
}
