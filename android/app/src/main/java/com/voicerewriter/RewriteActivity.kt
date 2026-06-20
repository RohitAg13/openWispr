package com.voicerewriter

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme

class RewriteActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "com.voicerewriter.MODE"
        const val EXTRA_AUTO_RECORD = "com.voicerewriter.AUTO_RECORD"
    }

    private lateinit var repo: SettingsRepository
    private lateinit var audioRecorder: AudioRecorder
    private val sourceState = mutableStateOf("") // selection (PROCESS_TEXT) or clipboard text
    private var readOnly: Boolean = false
    private var processTextMode: Boolean = false // launched from the selection toolbar
    private var voiceMode: Boolean = false        // launched from the bubble → dictation
    private var clipboardResolved: Boolean = false
    private var initialMode: String? = null       // mode requested by the bubble
    private var autoRecord: Boolean = false        // start recording on open (bubble flow)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)
        audioRecorder = AudioRecorder(applicationContext)

        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (processText != null) {
            // From the text-selection toolbar: chip-based rewrite, replace in place.
            processTextMode = true
            sourceState.value = processText.toString()
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        } else {
            // From the floating bubble: voice dictation / rewrite. The clipboard
            // (used as the target in Rewrite mode) can only be read once we have
            // window focus on Android 10+, so defer it to onWindowFocusChanged().
            voiceMode = true
            initialMode = intent.getStringExtra(EXTRA_MODE)
            autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                if (voiceMode) VoiceSheet() else RewriteSheet()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardResolved || !voiceMode) return
        // Best-effort: stash the clipboard for Rewrite mode. Empty is fine (Dictate
        // mode doesn't need it); we no longer auto-close on an empty clipboard.
        sourceState.value = readClipboardText()
        clipboardResolved = true
    }

    override fun onDestroy() {
        super.onDestroy()
        audioRecorder.cancel()
    }

    private fun readClipboardText(): String {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cb.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
    }

    /** PROCESS_TEXT path: replace the selection in place (or copy if read-only). */
    private fun accept(result: String) {
        if (!readOnly) {
            val data = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result)
            setResult(Activity.RESULT_OK, data)
        } else {
            setClipboard(result)
            Toast.makeText(this, "Copied (this field is read-only)", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    /**
     * Voice path: hand the result to the accessibility service and close. The
     * service inserts once the host app is back in front (it reports the outcome
     * via its own toast). If the service is off, fall back to the clipboard here.
     */
    private fun acceptVoice(result: String) {
        val enqueued = OpenWisprAccessibilityService.enqueueInsert(result)
        if (!enqueued) {
            setClipboard(result)
            Toast.makeText(this, "Copied. Enable accessibility to auto-insert.", Toast.LENGTH_LONG).show()
        }
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    // ---------------- voice sheet ----------------

    private enum class Stage { IDLE, RECORDING, TRANSCRIBING, PROCESSING, DONE, ERROR }

    @Composable
    private fun VoiceSheet() {
        val scope = rememberCoroutineScope()

        var settings by remember { mutableStateOf<Settings?>(null) }
        var mode by remember { mutableStateOf(initialMode ?: Defaults.MODE_DICTATE) }
        var stage by remember { mutableStateOf(Stage.IDLE) }
        var transcript by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var streamJob by remember { mutableStateOf<Job?>(null) }
        var ampJob by remember { mutableStateOf<Job?>(null) }
        var pendingStart by remember { mutableStateOf(false) }

        // Only sets a flag; an effect below performs the actual start (avoids
        // referencing local fns that are declared later).
        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) pendingStart = true
            else { error = "Microphone permission denied."; stage = Stage.ERROR }
        }

        // After we have a transcript: clean it up (Dictate) or apply it as an
        // instruction to the clipboard text (Rewrite).
        fun process(s: Settings, spoken: String) {
            transcript = spoken
            output = ""
            error = null
            if (mode == Defaults.MODE_REWRITE) {
                val target = sourceState.value
                if (target.isBlank()) {
                    error = "Rewrite mode needs copied text. Copy something first, or switch to Dictate."
                    stage = Stage.ERROR
                    return
                }
                stage = Stage.PROCESSING
                val flow = RewriteEngine.streamInstruction(s, spoken, target)
                streamJob = scope.launch { collectInto(flow, { output += it }, { e ->
                    error = e; stage = Stage.ERROR
                }, { output = RewriteEngine.cleanOutput(output); stage = Stage.DONE }) }
            } else {
                // Dictate fresh: optionally clean the raw transcript with the LLM.
                if (!s.cleanupDictation) {
                    output = spoken
                    stage = Stage.DONE
                    return
                }
                stage = Stage.PROCESSING
                val flow = RewriteEngine.streamDictationCleanup(s, spoken)
                streamJob = scope.launch { collectInto(flow, { output += it }, { e ->
                    error = e; stage = Stage.ERROR
                }, { output = RewriteEngine.cleanOutput(output); stage = Stage.DONE }) }
            }
        }

        // Stop recording, revert the bubble, transcribe (local or cloud), then process.
        fun stopRecording(s: Settings) {
            if (stage != Stage.RECORDING) return
            BubbleService.recordingStopper = null
            ampJob?.cancel()
            BubbleService.instance?.showIdle()
            val local = s.sttProvider == "local"
            // On-device wants float samples; cloud wants an uploadable WAV.
            val floats = if (local) audioRecorder.stopToFloats() else null
            val wav = if (local) null else audioRecorder.stopToWav()
            if ((local && floats == null) || (!local && wav == null)) {
                error = "Didn't catch any audio. Tap and speak a little longer."
                stage = Stage.ERROR
                return
            }
            stage = Stage.TRANSCRIBING
            streamJob = scope.launch {
                try {
                    val text = if (local) {
                        LocalWhisperStt.transcribe(this@RewriteActivity, floats!!)
                    } else {
                        SttEngine.transcribe(s, wav!!).also { wav.delete() }
                    }
                    if (text.isBlank()) { error = "Empty transcript. Try again."; stage = Stage.ERROR }
                    else process(s, text)
                } catch (e: Exception) {
                    wav?.delete(); error = e.message ?: e.toString(); stage = Stage.ERROR
                }
            }
        }

        // Begin recording: switch the bubble to a live waveform and feed amplitudes.
        fun startRecording(s: Settings) {
            error = null; transcript = ""; output = ""
            try { audioRecorder.start() }
            catch (e: Exception) { error = e.message ?: "Couldn't start the mic."; stage = Stage.ERROR; return }
            stage = Stage.RECORDING
            BubbleService.instance?.showRecording()
            BubbleService.recordingStopper = { stopRecording(s) }
            ampJob?.cancel()
            ampJob = scope.launch {
                while (isActive && audioRecorder.isRecording) {
                    BubbleService.instance?.showAmplitude(audioRecorder.amplitude())
                    delay(80)
                }
            }
        }

        fun ensurePermissionThenRecord(s: Settings) {
            if (s.sttProvider == "local") {
                if (!WhisperModelManager.isReady(this)) {
                    error = "On-device model not downloaded. Open Settings → Voice → Download model."
                    stage = Stage.ERROR
                    return
                }
            } else if (!s.isSttConfigured) {
                error = "No speech-to-text key set. Open OpenWispr settings."
                stage = Stage.ERROR
                return
            }
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) startRecording(s) else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        fun onMicTap() {
            val s = settings ?: return
            when (stage) {
                Stage.RECORDING -> stopRecording(s)
                Stage.IDLE, Stage.DONE, Stage.ERROR -> ensurePermissionThenRecord(s)
                else -> {} // busy (transcribing / processing): ignore
            }
        }

        LaunchedEffect(Unit) {
            val s = repo.get()
            settings = s
            if (initialMode == null) mode = s.defaultMode
            if (autoRecord) ensurePermissionThenRecord(s)
        }
        // Start once the permission dialog returns granted.
        LaunchedEffect(pendingStart) {
            if (pendingStart) { pendingStart = false; settings?.let { startRecording(it) } }
        }
        // While recording in the bubble flow, hide our window so only the bubble's
        // waveform shows over the live host app (no dim).
        val recordingHidden = stage == Stage.RECORDING && autoRecord
        LaunchedEffect(recordingHidden) {
            if (recordingHidden) window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            else window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        // Voice flow auto-inserts: when the rewrite is ready, send it straight to
        // the focused field instead of waiting for an Insert tap (Wispr-style).
        LaunchedEffect(stage) {
            if (stage == Stage.DONE && output.isNotBlank()) acceptVoice(output)
        }
        // Always release the bubble + mic if we leave mid-recording.
        DisposableEffect(Unit) {
            onDispose {
                BubbleService.recordingStopper = null
                ampJob?.cancel()
                BubbleService.instance?.showIdle()
            }
        }

        if (recordingHidden) {
            // No sheet: the bubble is the recording UI. Tap anywhere (or the bubble) to stop.
            Box(modifier = Modifier.fillMaxSize().clickable { onMicTap() })
            return
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Transparent),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "OpenWispr",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    // Mode toggle
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = mode == Defaults.MODE_DICTATE,
                            onClick = { if (stage != Stage.RECORDING) mode = Defaults.MODE_DICTATE },
                            label = { Text("Dictate fresh") },
                        )
                        FilterChip(
                            selected = mode == Defaults.MODE_REWRITE,
                            onClick = { if (stage != Stage.RECORDING) mode = Defaults.MODE_REWRITE },
                            label = { Text("Rewrite clipboard") },
                        )
                    }

                    if (mode == Defaults.MODE_REWRITE && sourceState.value.isNotBlank()) {
                        Text(
                            text = "On: \"${sourceState.value.take(120)}${if (sourceState.value.length > 120) "…" else ""}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Mic button
                    val micLabel = when (stage) {
                        Stage.RECORDING -> "■  Stop & process"
                        Stage.TRANSCRIBING -> "Transcribing…"
                        Stage.PROCESSING -> "Rewriting…"
                        else -> if (mode == Defaults.MODE_REWRITE) "🎤  Speak a command" else "🎤  Tap to speak"
                    }
                    Button(
                        onClick = { onMicTap() },
                        enabled = stage != Stage.TRANSCRIBING && stage != Stage.PROCESSING,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (stage == Stage.TRANSCRIBING || stage == Stage.PROCESSING) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("   $micLabel")
                        } else {
                            Text(micLabel)
                        }
                    }

                    // Output / status
                    when {
                        error != null -> Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        output.isNotEmpty() -> Column(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(text = output, style = MaterialTheme.typography.bodyLarge)
                        }
                        transcript.isNotEmpty() -> Text(
                            text = transcript,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            streamJob?.cancel()
                            ampJob?.cancel()
                            BubbleService.recordingStopper = null
                            BubbleService.instance?.showIdle()
                            audioRecorder.cancel()
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Discard")
                        }
                        TextButton(
                            enabled = stage == Stage.DONE && output.isNotBlank(),
                            onClick = { acceptVoice(output) },
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Insert")
                        }
                    }
                }
            }
        }
    }

    /** Collect a streaming Flow<String>, forwarding chunks/errors/completion. */
    private suspend fun collectInto(
        flow: kotlinx.coroutines.flow.Flow<String>,
        onChunk: (String) -> Unit,
        onError: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        var failed = false
        flow.catch { e -> failed = true; onError(e.message ?: e.toString()) }
            .onCompletion { cause -> if (cause == null && !failed) onDone() }
            .collect { chunk -> onChunk(chunk) }
    }

    // ---------------- PROCESS_TEXT chip sheet (unchanged) ----------------

    @Composable
    private fun RewriteSheet() {
        val scope = rememberCoroutineScope()

        var selectedAction by remember { mutableStateOf<String?>(null) }
        var output by remember { mutableStateOf("") }
        var streaming by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var streamJob by remember { mutableStateOf<Job?>(null) }

        fun run(actionId: String) {
            streamJob?.cancel()
            selectedAction = actionId
            output = ""
            error = null
            done = false
            streaming = true
            streamJob = scope.launch {
                val settings = repo.get()
                if (!settings.isConfigured) {
                    error = "No API key set. Open OpenWispr to configure it."
                    streaming = false
                    return@launch
                }
                RewriteEngine.stream(settings, actionId, sourceState.value)
                    .catch { e ->
                        error = e.message ?: e.toString()
                        streaming = false
                    }
                    .onCompletion { cause ->
                        if (cause == null && error == null) {
                            output = RewriteEngine.cleanOutput(output)
                            done = true
                        }
                        streaming = false
                    }
                    .collect { chunk -> output += chunk }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Rewrite",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    ActionChips(
                        selected = selectedAction,
                        enabled = !streaming,
                        onPick = ::run,
                    )

                    when {
                        error != null -> Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        selectedAction == null -> Text(
                            text = sourceState.value.ifEmpty { "Reading clipboard…" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = output.ifEmpty { "…" },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (streaming) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        if (selectedAction != null && !streaming && error == null) {
                            TextButton(onClick = { run(selectedAction!!) }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("  Redo")
                            }
                        }
                        TextButton(onClick = {
                            streamJob?.cancel()
                            setResult(Activity.RESULT_CANCELED)
                            finish()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Discard")
                        }
                        TextButton(
                            enabled = done && output.isNotBlank(),
                            onClick = { accept(output) },
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(if (readOnly) "  Copy" else "  Accept")
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ActionChips(
        selected: String?,
        enabled: Boolean,
        onPick: (String) -> Unit,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (action in Defaults.ACTIONS) {
                AssistChip(
                    onClick = { if (enabled) onPick(action.id) },
                    enabled = enabled || selected == action.id,
                    label = { Text(action.title) },
                )
            }
        }
    }
}
