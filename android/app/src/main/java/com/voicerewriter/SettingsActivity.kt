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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                SettingsScreen(repo) { lifecycleScope.launch { it() } }
            }
        }
    }
}

private fun startBubble(ctx: Context) =
    ContextCompat.startForegroundService(ctx, Intent(ctx, BubbleService::class.java))

private fun stopBubble(ctx: Context) =
    ctx.stopService(Intent(ctx, BubbleService::class.java))

/** Whether our accessibility service is enabled in system settings (immediate check). */
private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val expected = "${ctx.packageName}/${OpenWisprAccessibilityService::class.java.name}"
    val enabled = android.provider.Settings.Secure.getString(
        ctx.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(repo: SettingsRepository, launch: (suspend () -> Unit) -> Unit) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }

    var bubbleOn by remember { mutableStateOf(BubbleService.isRunning) }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (android.provider.Settings.canDrawOverlays(context)) {
            startBubble(context)
            bubbleOn = true
        } else {
            Toast.makeText(context, "Permission needed to show the bubble", Toast.LENGTH_SHORT).show()
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the bubble works regardless; this just lets its notification show */ }

    fun enableBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!android.provider.Settings.canDrawOverlays(context)) {
            overlayLauncher.launch(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                )
            )
        } else {
            startBubble(context)
            bubbleOn = true
        }
    }

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
    var llmProgress by remember { mutableStateOf<Float?>(null) } // non-null while downloading

    // Voice / speech-to-text
    var sttProvider by remember { mutableStateOf(Defaults.DEFAULT_STT_PROVIDER) }
    var sttEndpoint by remember { mutableStateOf("") }
    var sttKey by remember { mutableStateOf("") }
    var sttModel by remember { mutableStateOf("") }
    var defaultMode by remember { mutableStateOf(Defaults.MODE_DICTATE) }
    var deterministicCleanup by remember { mutableStateOf(true) }
    var cleanupDictation by remember { mutableStateOf(true) }
    var localLlmPolish by remember { mutableStateOf(false) }
    var vadAutoStop by remember { mutableStateOf(true) }
    var sttProviderMenu by remember { mutableStateOf(false) }
    var a11yEnabled by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var modelProgress by remember { mutableStateOf<Float?>(null) } // non-null while downloading
    var sttModelMenu by remember { mutableStateOf(false) }

    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { a11yEnabled = isAccessibilityEnabled(context) }

    LaunchedEffect(Unit) {
        val s = repo.get()
        provider = s.provider
        model = s.model
        customEndpoint = s.customEndpoint
        apiKey = s.apiKey
        voice = s.voice
        antiAI = s.antiAI
        temperature = s.temperature.toFloat()
        sttProvider = s.sttProvider
        sttEndpoint = s.sttEndpoint
        sttKey = s.sttKey
        sttModel = s.sttModel
        defaultMode = s.defaultMode
        deterministicCleanup = s.deterministicCleanup
        cleanupDictation = s.cleanupDictation
        localLlmPolish = s.localLlmPolish
        vadAutoStop = s.vadAutoStop
        a11yEnabled = isAccessibilityEnabled(context)
        loaded = true
    }
    // Refresh on-device LLM readiness when the provider/model selection changes.
    LaunchedEffect(provider, model) {
        llmReady = provider == "local" && LlmModelManager.isReady(context, model)
    }
    // Normalize + refresh on-device Whisper readiness for the selected model.
    LaunchedEffect(sttProvider, sttModel) {
        if (sttProvider == "local") {
            if (WhisperModelManager.MODELS.none { it.id == sttModel }) {
                sttModel = WhisperModelManager.DEFAULT_MODEL
            }
            modelReady = WhisperModelManager.isReady(context, sttModel)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("OpenWispr") }) }) { pad ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Select text in any app → tap “Rewrite” in the popup toolbar. " +
                    "Or use the floating bubble: copy text, tap the bubble, rewrite, paste.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Floating bubble", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Always-on button to rewrite copied text from any app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = bubbleOn,
                    onCheckedChange = { want ->
                        if (want) enableBubble() else {
                            stopBubble(context)
                            bubbleOn = false
                        }
                    },
                )
            }

            // Provider
            ExposedDropdownMenuBox(
                expanded = providerMenu,
                onExpandedChange = { providerMenu = it },
            ) {
                OutlinedTextField(
                    value = Defaults.PROVIDERS[provider]?.label ?: provider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = providerMenu) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = providerMenu,
                    onDismissRequest = { providerMenu = false },
                ) {
                    Defaults.PROVIDERS.values.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                provider = p.id
                                if (p.id != "custom" && p.defaultModel.isNotEmpty()) model = p.defaultModel
                                providerMenu = false
                            },
                        )
                    }
                }
            }

            Defaults.PROVIDERS[provider]?.let { p ->
                if (p.keyHelp.isNotEmpty()) {
                    Text(
                        p.keyHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (provider == "local") {
                // On-device LLM: model picker + one-time download.
                ExposedDropdownMenuBox(
                    expanded = llmModelMenu,
                    onExpandedChange = { llmModelMenu = it },
                ) {
                    val sel = LlmModelManager.model(model)
                    OutlinedTextField(
                        value = "${sel.label} (${sel.sizeLabel})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("On-device model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = llmModelMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = llmModelMenu,
                        onDismissRequest = { llmModelMenu = false },
                    ) {
                        LlmModelManager.MODELS.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${m.label} (${m.sizeLabel})") },
                                onClick = { model = m.id; llmModelMenu = false },
                            )
                        }
                    }
                }
                val downloading = llmProgress != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                llmReady -> "Ready — runs offline on this phone."
                                downloading -> "Downloading… ${((llmProgress ?: 0f) * 100).toInt()}%"
                                else -> "Not downloaded (${LlmModelManager.model(model).sizeLabel}, one time)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (llmReady) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!llmReady) {
                        Button(
                            enabled = !downloading,
                            onClick = {
                                val id = model
                                llmProgress = 0f
                                launch {
                                    try {
                                        LlmModelManager.download(context, id) { p -> llmProgress = p }
                                        llmReady = true
                                        llmProgress = null
                                        Toast.makeText(context, "Model ready", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        llmProgress = null
                                        Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        ) { Text(if (downloading) "…" else "Download") }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { llmProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Small on-device models are best for dictation cleanup; full voice rewriting is weaker than cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (provider == "custom") {
                    OutlinedTextField(
                        value = customEndpoint,
                        onValueChange = { customEndpoint = it },
                        label = { Text("Endpoint URL") },
                        placeholder = { Text("https://…/v1/chat/completions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    supportingText = {
                        Defaults.PROVIDERS[provider]?.modelHint?.let { Text(it) }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = voice,
                onValueChange = { voice = it },
                label = { Text("Voice profile (optional)") },
                placeholder = { Text("Describe how you write, or paste a few samples.") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Anti-AI guardrails", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Ban the obvious LLM tells.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = antiAI, onCheckedChange = { antiAI = it })
            }

            Column {
                Text("Temperature: ${"%.2f".format(temperature)}")
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..1.5f,
                )
            }

            // ---------------- Voice dictation ----------------
            Text(
                "Voice",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Tap the bubble, speak, and the result is typed into the field you're in. " +
                    "Dictate fresh text, or copy text and speak a command to rewrite it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Speech-to-text provider
            ExposedDropdownMenuBox(
                expanded = sttProviderMenu,
                onExpandedChange = { sttProviderMenu = it },
            ) {
                OutlinedTextField(
                    value = Defaults.STT_PROVIDERS[sttProvider]?.label ?: sttProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Speech-to-text provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttProviderMenu) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                )
                ExposedDropdownMenu(
                    expanded = sttProviderMenu,
                    onDismissRequest = { sttProviderMenu = false },
                ) {
                    Defaults.STT_PROVIDERS.values.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.label) },
                            onClick = {
                                sttProvider = p.id
                                if (p.id != "custom" && p.defaultModel.isNotEmpty()) sttModel = p.defaultModel
                                sttProviderMenu = false
                            },
                        )
                    }
                }
            }

            Defaults.STT_PROVIDERS[sttProvider]?.let { p ->
                if (p.keyHelp.isNotEmpty()) {
                    Text(
                        p.keyHelp,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (sttProvider == "local") {
                // On-device Whisper: model picker + one-time download.
                ExposedDropdownMenuBox(
                    expanded = sttModelMenu,
                    onExpandedChange = { sttModelMenu = it },
                ) {
                    val sel = WhisperModelManager.model(sttModel)
                    OutlinedTextField(
                        value = "${sel.label} (${sel.sizeLabel})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Whisper model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sttModelMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = sttModelMenu,
                        onDismissRequest = { sttModelMenu = false },
                    ) {
                        WhisperModelManager.MODELS.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${m.label} (${m.sizeLabel})") },
                                onClick = { sttModel = m.id; sttModelMenu = false },
                            )
                        }
                    }
                }
                val downloading = modelProgress != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                modelReady -> "Ready — transcription runs offline on this phone."
                                downloading -> "Downloading… ${((modelProgress ?: 0f) * 100).toInt()}%"
                                else -> "Not downloaded (${WhisperModelManager.model(sttModel).sizeLabel}, one time)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (modelReady) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!modelReady) {
                        Button(
                            enabled = !downloading,
                            onClick = {
                                val id = sttModel
                                modelProgress = 0f
                                launch {
                                    try {
                                        WhisperModelManager.download(context, id) { p -> modelProgress = p }
                                        modelReady = true
                                        modelProgress = null
                                        Toast.makeText(context, "Model ready", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        modelProgress = null
                                        Toast.makeText(context, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                        ) { Text(if (downloading) "…" else "Download") }
                    }
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { modelProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    "Smaller models are faster and lighter on memory; Small is most accurate but heavy on phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (sttProvider == "custom") {
                    OutlinedTextField(
                        value = sttEndpoint,
                        onValueChange = { sttEndpoint = it },
                        label = { Text("Transcription endpoint URL") },
                        placeholder = { Text("https://…/v1/audio/transcriptions") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = sttModel,
                    onValueChange = { sttModel = it },
                    label = { Text("Speech-to-text model") },
                    supportingText = { Defaults.STT_PROVIDERS[sttProvider]?.modelHint?.let { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = sttKey,
                    onValueChange = { sttKey = it },
                    label = { Text("Speech-to-text API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Default voice mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Default mode: rewrite clipboard", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "On = speak a command to rewrite copied text. Off = dictate fresh text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = defaultMode == Defaults.MODE_REWRITE,
                    onCheckedChange = {
                        defaultMode = if (it) Defaults.MODE_REWRITE else Defaults.MODE_DICTATE
                    },
                )
            }

            // Deterministic cleanup (fast, offline, no model)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Smart cleanup (instant)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Rule-based, offline: removes fillers, converts spoken punctuation/numbers " +
                            "(\"question mark\"→?, \"twenty three\"→23) and resolves self-corrections.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = deterministicCleanup, onCheckedChange = { deterministicCleanup = it })
            }

            // Optional LLM polish on top — provider-aware (off by default on-device).
            val isLocalProvider = provider == "local"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Polish with LLM", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (isLocalProvider)
                            "After smart cleanup, also run the text through the on-device model. " +
                                "Off by default — small models tend to hallucinate and over-edit; " +
                                "smart cleanup alone is usually better."
                        else
                            "After smart cleanup, also run the text through the cloud LLM for grammar and flow.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = if (isLocalProvider) localLlmPolish else cleanupDictation,
                    onCheckedChange = { if (isLocalProvider) localLlmPolish = it else cleanupDictation = it },
                )
            }

            // Auto-stop on silence (Silero VAD)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-stop when you pause", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "On-device voice detection (Silero VAD) stops recording after a short pause and trims silence.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = vadAutoStop, onCheckedChange = { vadAutoStop = it })
            }

            // Personal dictionary (names/terms the recognizer mishears)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Personal dictionary", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Fix names, emails and jargon the recognizer gets wrong — corrected on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = { context.startActivity(Intent(context, VocabActivity::class.java)) }) {
                    Text("Manage")
                }
            }

            // Accessibility insertion
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-insert (accessibility)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (a11yEnabled) "Enabled — results type into the focused field."
                        else "Disabled — results are copied to the clipboard to paste manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (a11yEnabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = {
                    accessibilityLauncher.launch(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }) {
                    Text(if (a11yEnabled) "Manage" else "Enable")
                }
            }

            Button(
                onClick = {
                    launch {
                        repo.save(
                            Settings(
                                provider = provider,
                                model = model.trim(),
                                customEndpoint = customEndpoint.trim(),
                                apiKey = apiKey.trim(),
                                voice = voice,
                                antiAI = antiAI,
                                temperature = temperature.toDouble(),
                                sttProvider = sttProvider,
                                sttEndpoint = sttEndpoint.trim(),
                                sttKey = sttKey.trim(),
                                sttModel = sttModel.trim(),
                                defaultMode = defaultMode,
                                deterministicCleanup = deterministicCleanup,
                                cleanupDictation = cleanupDictation,
                                localLlmPolish = localLlmPolish,
                                vadAutoStop = vadAutoStop,
                            )
                        )
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}
