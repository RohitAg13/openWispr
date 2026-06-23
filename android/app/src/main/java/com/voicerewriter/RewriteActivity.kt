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
import com.voicerewriter.textproc.AppContext
import com.voicerewriter.textproc.TextProcessor
import com.voicerewriter.textproc.TextProcessingConfig
import com.voicerewriter.textproc.VocabCorrector
import com.voicerewriter.ui.BrandAmber
import com.voicerewriter.ui.BrandCoral
import com.voicerewriter.ui.BrandRose
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.SunsetBrush
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

class RewriteActivity : ComponentActivity() {

    companion object {
        const val EXTRA_MODE = "com.voicerewriter.MODE"
        const val EXTRA_AUTO_RECORD = "com.voicerewriter.AUTO_RECORD"

        /**
         * How long the corrected text is shown before it auto-inserts — scaled to the
         * text length so there's always time to read it. Min 2s, up to 6s.
         */
        private fun editWindowMs(text: String): Long {
            val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
            return (2000L + words * 110L).coerceIn(2000L, 6000L)
        }
    }

    private lateinit var repo: SettingsRepository
    private lateinit var audioRecorder: AudioRecorder
    private val sourceState = mutableStateOf("") // selection (PROCESS_TEXT) or clipboard text
    private var readOnly: Boolean = false
    private var processTextMode: Boolean = false // launched from the selection toolbar
    private var voiceMode: Boolean = false        // launched from the bubble
    private var clipboardResolved: Boolean = false
    private var initialMode: String? = null       // mode requested by the bubble
    private var autoRecord: Boolean = false        // start recording on open (dictation)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(applicationContext)
        audioRecorder = AudioRecorder(applicationContext)

        val processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
        if (processText != null) {
            processTextMode = true
            sourceState.value = processText.toString()
            readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        } else {
            voiceMode = true
            initialMode = intent.getStringExtra(EXTRA_MODE)
            autoRecord = intent.getBooleanExtra(EXTRA_AUTO_RECORD, false)
        }

        setContent {
            OpenWisprTheme {
                when {
                    processTextMode ->
                        ChipRewriteSheet(
                            title = "Rewrite",
                            acceptLabel = if (readOnly) "Copy" else "Accept",
                            onAccept = ::accept,
                        )
                    voiceMode && initialMode == Defaults.MODE_TRANSFORM ->
                        ChipRewriteSheet(
                            title = "Transform copied text",
                            acceptLabel = "Insert",
                            onAccept = ::acceptVoice,
                        )
                    else -> VoiceSheet()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || clipboardResolved || !voiceMode) return
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
            setResult(Activity.RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, result))
        } else {
            setClipboard(result)
            Toast.makeText(this, "Copied (this field is read-only)", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }

    /** Voice path: hand the result to the accessibility service (auto-insert) and close. */
    private fun acceptVoice(result: String) {
        val enqueued = OpenWisprAccessibilityService.enqueueInsert(result)
        if (!enqueued) {
            setClipboard(result)
            Toast.makeText(this, "Copied. Enable accessibility to auto-insert.", Toast.LENGTH_LONG).show()
        }
        LastDictation.set(this, result)
        postFixNotification()
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    /**
     * Host app captured the moment recording starts. Read this (not the live
     * [OpenWisprAccessibilityService.lastHostPackage]) downstream — by the time a
     * dictation finishes, transient system windows may have moved focus, which used
     * to mislabel entries as "System UI".
     */
    private var dictationHostPkg: String? = null

    /** App context of the in-flight dictation, captured in [process] for corpus tagging. */
    private var dictationCategory: AppContext.Category = AppContext.Category.GENERIC

    /**
     * Save an accepted dictation to the personalization corpus (off-thread). [cleaned] is
     * what the pipeline produced; [final] is what the user kept — equal when they didn't edit.
     */
    private fun recordCorpus(cleaned: String, final: String, edited: Boolean) {
        val ctx = applicationContext
        val cat = dictationCategory.key
        Thread {
            runCatching {
                CorrectionCorpus.record(ctx, CorrectionSample(
                    ts = System.currentTimeMillis(), category = cat,
                    cleaned = cleaned, final = final, edited = edited,
                ))
            }
        }.start()
    }

    /**
     * Render past corrections as a compact few-shot block for the polish prompt. Each
     * example is truncated so a couple of them can't blow the tiny model's context budget.
     */
    private fun fewShotBlock(samples: List<CorrectionSample>): String {
        val usable = samples.filter { it.cleaned.isNotBlank() && it.final.isNotBlank() }
        if (usable.isEmpty()) return ""
        fun clip(s: String) = s.trim().let { if (it.length > 240) it.take(240) + "…" else it }
        return buildString {
            append("Examples of how I like my dictation cleaned (raw, then the version I keep):")
            for (s in usable) {
                append("\nRaw: ").append(clip(s.cleaned))
                append("\nKept: ").append(clip(s.final))
            }
        }
    }

    /** Log a finished dictation to the Home feed (off-thread; no-op when history is disabled). */
    private fun recordHistory(before: String, after: String, durationSec: Int, edited: Boolean, onDevice: Boolean) {
        if (after.isBlank()) return
        val pkg = (dictationHostPkg ?: OpenWisprAccessibilityService.lastHostPackage).orEmpty()
        val label = appLabel(pkg)
        val words = after.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val entry = DictationEntry(
            id = "${System.currentTimeMillis()}-${after.hashCode() and 0xffff}",
            timestamp = System.currentTimeMillis(),
            appPackage = pkg,
            appLabel = label,
            durationSec = durationSec,
            words = words,
            accepted = !edited,
            onDevice = onDevice,
            before = before,
            after = after,
        )
        val ctx = applicationContext
        Thread { runCatching { DictationHistory.record(ctx, entry) } }.start()
    }

    /**
     * In chat/messaging apps, strip the single trailing full stop Whisper tends to add to a
     * short one-liner ("On my way." -> "On my way"). Only touches a *single-sentence* message
     * ending in a lone "." — never "!"/"?", never an ellipsis, and never multi-sentence text
     * (where the period is doing real work). Question/exclamation marks are left untouched.
     */
    private fun dropChatTerminalPeriod(text: String, category: AppContext.Category): String {
        if (category != AppContext.Category.CHAT && category != AppContext.Category.SOCIAL) return text
        val t = text.trimEnd()
        if (!t.endsWith(".") || t.endsWith("..")) return text // keep ellipses
        val body = t.dropLast(1).trimEnd()
        if (body.isEmpty()) return text
        // Only for a single sentence — bail if there's another terminator or a line break inside.
        if (body.any { it == '.' || it == '!' || it == '?' || it == '\n' }) return text
        return body
    }

    private fun appLabel(pkg: String): String {
        if (pkg.isBlank()) return "Dictation"
        return runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() })
    }

    /** Quiet, replace-in-place notification: tap to fix a mis-heard word. */
    private fun postFixNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        // On Android 13+ this silently no-ops without POST_NOTIFICATIONS — skip cleanly.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val channelId = "dictation_fix"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId, "Dictation corrections", android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Tap to fix a mis-heard name after dictation." }
            nm.createNotificationChannel(ch)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, FixDictationActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_aperture)
            .setContentTitle("Dictated ✓")
            .setContentText("Got a name wrong? Tap to fix.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(42, notif) }
    }

    private fun setClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("rewrite", text))
    }

    /**
     * Teach the dictionary from an inline edit (wrong→right for the words the user
     * fixed). Fire-and-forget on a worker thread so it survives this activity
     * finishing right after insert.
     */
    private fun learnFromEditAsync(original: String, edited: String) {
        if (original == edited) return
        val ctx = applicationContext
        Thread {
            runCatching { kotlinx.coroutines.runBlocking { VocabRepository(ctx).learnFromEdit(original, edited) } }
        }.start()
    }

    private fun streamFor(s: Settings, prompt: String, text: String): kotlinx.coroutines.flow.Flow<String> =
        if (s.provider == "local") LocalLlmEngine.streamWithPrompt(applicationContext, s, prompt, text)
        else RewriteEngine.streamWithPrompt(s, prompt, text)

    /** Map a raw exception to plain, actionable copy for the sheet. */
    private fun friendlyError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        val low = msg.lowercase()
        return when {
            e is java.net.UnknownHostException || e is java.net.ConnectException ||
                "unable to resolve host" in low || "failed to connect" in low ->
                "No connection — check your network, or switch to on-device in Settings."
            e is java.net.SocketTimeoutException || "timeout" in low || "timed out" in low ->
                "That took too long — try again."
            "401" in low || "403" in low || "unauthor" in low || "api key" in low || "invalid key" in low ->
                "Check your API key in Settings."
            else -> msg
        }
    }

    private fun llmReady(s: Settings): Boolean =
        if (s.provider == "local") LlmModelManager.isReady(this, s.model) else s.isConfigured

    // ---------------- voice dictation sheet ----------------

    private enum class Stage { IDLE, RECORDING, TRANSCRIBING, CORRECTING, REVIEW, ERROR }

    @Composable
    private fun VoiceSheet() {
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current

        var settings by remember { mutableStateOf<Settings?>(null) }
        var stage by remember { mutableStateOf(Stage.IDLE) }
        var transcript by remember { mutableStateOf("") }   // raw STT (shown immediately)
        var output by remember { mutableStateOf("") }       // LLM streaming buffer
        var finalText by remember { mutableStateOf("") }    // corrected text to insert
        var error by remember { mutableStateOf<String?>(null) }
        var notice by remember { mutableStateOf<String?>(null) }  // soft, non-blocking note in REVIEW
        var streamJob by remember { mutableStateOf<Job?>(null) }
        var ampJob by remember { mutableStateOf<Job?>(null) }
        var pendingStart by remember { mutableStateOf(false) }
        var editing by remember { mutableStateOf(false) }
        var editText by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(1f) }
        var recStartMs by remember { mutableStateOf(0L) }
        var durationSec by remember { mutableStateOf(0) }
        val amps = remember { mutableStateListOf<Float>() }
        val editFocus = remember { FocusRequester() }

        val micPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) pendingStart = true
            else { error = "Microphone permission denied."; stage = Stage.ERROR }
        }

        fun toReview(text: String) {
            // A blank result (e.g. cleanup or polish ate everything) should not leave an empty sheet.
            if (text.isBlank()) { error = "Nothing to insert — try again."; stage = Stage.ERROR; return }
            finalText = text; editText = text; countdown = 1f; editing = false; stage = Stage.REVIEW
        }

        fun process(s: Settings, spoken: String) {
            transcript = spoken; output = ""; error = null; notice = null
            val host = dictationHostPkg ?: OpenWisprAccessibilityService.lastHostPackage
            // App-context up front: drives code handling, the chat-period rule, and polish tone.
            val category = AppContext.categoryFor(host, spoken)
            dictationCategory = category
            val isCode = category == AppContext.Category.CODE
            val cleaned0 = if (s.deterministicCleanup)
                TextProcessor.process(spoken, TextProcessingConfig(), isCodeContext = isCode) else spoken
            // Chat/messaging: drop the trailing full stop Whisper adds to short one-liners — a
            // period on a single casual message reads as terse/formal, which people don't want.
            val cleaned = dropChatTerminalPeriod(cleaned0, category)
            // Guards (ported from the cleanup pipeline's LocalLLMProcessor): skip the LLM where it tends to
            // harm rather than help — polish off, very short input, or code/terminal context
            // (the deterministic stage already handles those; code only goes to the LLM at FULL).
            val wordCount = cleaned.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            if (!s.llmPolishEnabled || wordCount < 4 || (isCode && s.polishLevel != PolishLevel.FULL)) {
                toReview(cleaned); return
            }
            stage = Stage.CORRECTING
            val relaxed = RewriteEngine.hasSelfCorrection(spoken)
            streamJob = scope.launch {
                val tone = AppToneRepository(this@RewriteActivity).toneFor(category)
                // L3: at Medium/Full, show the model how THIS user likes their dictation cleaned,
                // using their closest past corrections as few-shot (skipped at Light to keep it cheap).
                val examples = if (s.polishLevel == PolishLevel.MEDIUM || s.polishLevel == PolishLevel.FULL)
                    withContext(Dispatchers.IO) {
                        fewShotBlock(CorrectionCorpus.similar(applicationContext, cleaned, category.key, k = 2))
                    } else ""
                // The cleanup level (light/medium/full) tunes how much the model may edit.
                val prompt = buildString {
                    append(Defaults.DICTATION_PROMPT)
                    if (s.polishLevel.instruction.isNotEmpty()) append("\n\n").append(s.polishLevel.instruction)
                    if (tone.isNotBlank()) append("\n\nTone for this app: ").append(tone)
                    if (examples.isNotEmpty()) append("\n\n").append(examples)
                }
                collectInto(
                    streamFor(s, prompt, cleaned),
                    { output += it },
                    { _ ->
                        // The polish is optional — never throw away a good transcript on its failure.
                        notice = "Couldn't polish — using the cleaned text."
                        toReview(cleaned)
                    },
                    {
                        // Content-preservation guard: if the model dropped too much or ballooned
                        // with invented content, fall back to the deterministic text.
                        val polished = RewriteEngine.cleanOutput(output)
                        if (polished.isBlank() || !RewriteEngine.preservesContent(cleaned, polished, relaxed)) {
                            notice = "Polish changed too much — using the cleaned text."
                            toReview(cleaned)
                        } else {
                            toReview(dropChatTerminalPeriod(polished, category))
                        }
                    },
                )
            }
        }

        fun stopRecording(s: Settings) {
            if (stage != Stage.RECORDING) return
            durationSec = ((System.currentTimeMillis() - recStartMs) / 1000L).toInt().coerceAtLeast(1)
            BubbleService.recordingStopper = null
            ampJob?.cancel()
            BubbleService.instance?.showIdle()
            val local = s.sttProvider == "local"
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
                    val vocab = VocabRepository(this@RewriteActivity).get()
                    // Personal vocab biases decoding (B3) on both backends, then snaps
                    // remaining near-misses afterward (B2).
                    val bias = if (vocab.isEmpty()) null else VocabCorrector.biasPrompt(vocab)
                    val raw = if (local) {
                        LocalWhisperStt.transcribe(this@RewriteActivity, s, floats!!, bias)
                    } else {
                        SttEngine.transcribe(s, wav!!, bias).also { wav.delete() }
                    }
                    val text = if (vocab.isEmpty()) raw else VocabCorrector.correct(raw, vocab)
                    if (text.isBlank()) { error = "Empty transcript. Try again."; stage = Stage.ERROR }
                    else process(s, text)
                } catch (e: Exception) {
                    wav?.delete(); error = friendlyError(e); stage = Stage.ERROR
                }
            }
        }

        fun startRecording(s: Settings) {
            error = null; notice = null; transcript = ""; output = ""; finalText = ""; amps.clear()
            // Snapshot the target app now, while it still has focus, before our sheet or any
            // system window can steal it (otherwise the entry gets mislabelled, e.g. "System UI").
            dictationHostPkg = OpenWisprAccessibilityService.lastHostPackage
            recStartMs = System.currentTimeMillis()
            try { audioRecorder.start(vadAutoStop = s.vadAutoStop, onAutoStop = { stopRecording(s) }) }
            catch (e: Exception) { error = e.message ?: "Couldn't start the mic."; stage = Stage.ERROR; return }
            stage = Stage.RECORDING
            BubbleService.instance?.showRecording()
            BubbleService.recordingStopper = { stopRecording(s) }
            ampJob?.cancel()
            ampJob = scope.launch {
                while (isActive && audioRecorder.isRecording) {
                    val raw = audioRecorder.amplitude()
                    BubbleService.instance?.showAmplitude(raw)
                    amps.add((raw / 14000f).coerceIn(0f, 1f))
                    if (amps.size > 56) amps.removeAt(0)
                    delay(55)
                }
            }
        }

        fun ensurePermissionThenRecord(s: Settings) {
            if (s.sttProvider == "local") {
                if (!WhisperModelManager.isReady(this, s.sttModel.ifBlank { WhisperModelManager.DEFAULT_MODEL })) {
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
                Stage.IDLE, Stage.REVIEW, Stage.ERROR -> ensurePermissionThenRecord(s)
                else -> {}
            }
        }

        fun cancelAndFinish() {
            streamJob?.cancel(); ampJob?.cancel()
            BubbleService.recordingStopper = null
            BubbleService.instance?.showIdle()
            audioRecorder.cancel()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        LaunchedEffect(Unit) {
            val s = repo.get(); settings = s
            if (autoRecord) ensurePermissionThenRecord(s)
        }
        LaunchedEffect(pendingStart) {
            if (pendingStart) { pendingStart = false; settings?.let { startRecording(it) } }
        }
        // Auto-insert after the edit window unless the user is editing.
        LaunchedEffect(stage, editing) {
            if (stage == Stage.REVIEW && !editing && finalText.isNotBlank()) {
                val total = editWindowMs(finalText)
                var e = 0L; val step = 16L
                while (e < total && isActive) {
                    countdown = 1f - e.toFloat() / total
                    delay(step); e += step
                }
                if (isActive && stage == Stage.REVIEW && !editing) {
                    recordHistory(transcript, finalText, durationSec, edited = false,
                        onDevice = settings?.sttProvider == "local")
                    recordCorpus(finalText, finalText, edited = false)
                    acceptVoice(finalText)
                }
            }
        }
        LaunchedEffect(editing) { if (editing) runCatching { editFocus.requestFocus() } }
        // Gentle cue when the result is ready to review (before it auto-inserts).
        LaunchedEffect(stage) {
            if (stage == Stage.REVIEW) runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        }
        DisposableEffect(Unit) {
            onDispose {
                BubbleService.recordingStopper = null
                ampJob?.cancel()
                BubbleService.instance?.showIdle()
            }
        }

        BottomSheet(onScrimTap = { if (stage == Stage.RECORDING) onMicTap() else cancelAndFinish() }) {
            SheetHeader(
                when (stage) {
                    Stage.RECORDING -> "Listening"
                    Stage.TRANSCRIBING -> "Transcribing"
                    Stage.CORRECTING -> "Polishing"
                    Stage.REVIEW -> "Ready"
                    else -> "OpenWispr"
                }
            )

            when (stage) {
                Stage.RECORDING -> {
                    ListeningOrb(amps, Modifier.fillMaxWidth().height(132.dp))
                    Text(
                        "Speak now — I'll stop when you pause.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { cancelAndFinish() }) { Text("Cancel") }
                        TextButton(onClick = { onMicTap() }) {
                            Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  Done")
                        }
                    }
                }

                Stage.TRANSCRIBING -> TranscribingRing("Transcribing")

                Stage.CORRECTING -> {
                    TranscribingRing("Polishing")
                    if (output.isNotBlank()) {
                        OutputText(RewriteEngine.cleanOutput(output))
                    }
                }

                Stage.REVIEW -> {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth().focusRequester(editFocus),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                cancelAndFinish()
                            }) {
                                Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Text("  Discard")
                            }
                            TextButton(onClick = {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                learnFromEditAsync(finalText, editText)
                                recordHistory(transcript, editText, durationSec, edited = true,
                                    onDevice = settings?.sttProvider == "local")
                                recordCorpus(finalText, editText, edited = true)
                                acceptVoice(editText)
                            }) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  Insert")
                            }
                        }
                    } else {
                        notice?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutputText(finalText)
                        LinearProgressIndicator(
                            progress = { countdown },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = {
                                runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                editing = true
                            }) {
                                Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Text("  Edit")
                            }
                            Row {
                                TextButton(onClick = {
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    cancelAndFinish()
                                }) { Text("Discard") }
                                TextButton(onClick = {
                                    runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    recordHistory(transcript, finalText, durationSec, edited = false,
                                        onDevice = settings?.sttProvider == "local")
                                    recordCorpus(finalText, finalText, edited = false)
                                    acceptVoice(finalText)
                                }) {
                                    Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  Insert")
                                }
                            }
                        }
                    }
                }

                Stage.IDLE, Stage.ERROR -> {
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { cancelAndFinish() }) { Text("Close") }
                        TextButton(onClick = { onMicTap() }) {
                            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text("  Try again")
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
        onError: (Throwable) -> Unit,
        onDone: () -> Unit,
    ) {
        var failed = false
        flow.catch { e -> failed = true; onError(e) }
            .onCompletion { cause -> if (cause == null && !failed) onDone() }
            .collect { chunk -> onChunk(chunk) }
    }

    // ---------------- chip rewrite sheet (selection + long-press transform) ----------------

    @Composable
    private fun ChipRewriteSheet(title: String, acceptLabel: String, onAccept: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        var selectedAction by remember { mutableStateOf<String?>(null) }
        var output by remember { mutableStateOf("") }
        var streaming by remember { mutableStateOf(false) }
        var done by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var streamJob by remember { mutableStateOf<Job?>(null) }

        fun run(actionId: String) {
            runCatching { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            streamJob?.cancel()
            selectedAction = actionId; output = ""; error = null; done = false; streaming = true
            streamJob = scope.launch {
                // Anti-AI guardrails humanize prose; they fight structured output, so
                // turn them off for Prompt Engineer (which needs its bold template).
                val settings = repo.get().let {
                    if (actionId == "prompt_engineer") it.copy(antiAI = false) else it
                }
                if (!llmReady(settings)) {
                    error = if (settings.provider == "local")
                        "On-device model not downloaded. Open OpenWispr → Save settings."
                    else "No API key set. Open OpenWispr to configure it."
                    streaming = false
                    return@launch
                }
                if (sourceState.value.isBlank()) {
                    error = "Nothing to transform — copy some text first."
                    streaming = false
                    return@launch
                }
                streamFor(settings, Defaults.DEFAULT_PROMPTS.getValue(actionId), sourceState.value)
                    .catch { e -> error = friendlyError(e); streaming = false }
                    .onCompletion { cause ->
                        if (cause == null && error == null) { output = RewriteEngine.cleanOutput(output); done = true }
                        streaming = false
                    }
                    .collect { chunk -> output += chunk }
            }
        }

        BottomSheet(onScrimTap = {
            streamJob?.cancel(); setResult(Activity.RESULT_CANCELED); finish()
        }) {
            SheetHeader(title)
            ChipRow(enabled = !streaming, selected = selectedAction, onPick = ::run)

            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                selectedAction == null -> Text(
                    sourceState.value.ifEmpty { "Reading clipboard…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                )
                streaming && output.isBlank() -> TranscribingRing("Working")
                else -> OutputText(RewriteEngine.cleanOutput(output).ifEmpty { "…" })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (streaming) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                if (selectedAction != null && !streaming && error == null) {
                    TextButton(onClick = { run(selectedAction!!) }) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text("  Redo")
                    }
                }
                TextButton(onClick = { streamJob?.cancel(); setResult(Activity.RESULT_CANCELED); finish() }) {
                    Icon(Icons.Default.Close, null, Modifier.size(18.dp)); Text("  Discard")
                }
                TextButton(enabled = done && output.isNotBlank(), onClick = { onAccept(output) }) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Text("  $acceptLabel")
                }
            }
        }
    }

    // ---------------- shared UI pieces ----------------

    @Composable
    private fun BottomSheet(onScrimTap: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // Tap above the sheet to dismiss/stop.
            Spacer(Modifier.fillMaxSize().clickable(onClick = onScrimTap))
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding(),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content,
                )
            }
        }
    }

    @Composable
    private fun SheetHeader(title: String) {
        // Mono uppercase "eyebrow" caption (handoff label spec).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(SunsetBrush))
            Text(
                title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun OutputText(text: String) {
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ChipRow(enabled: Boolean, selected: String?, onPick: (String) -> Unit) {
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

    /** Transcribing/Polishing: a rotating sunset ring around the cream Aperture mark. */
    @Composable
    private fun TranscribingRing(label: String) {
        val t = rememberInfiniteTransition(label = "ring")
        val angle by t.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "spin",
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    rotate(angle) {
                        val w = 4.dp.toPx()
                        drawArc(
                            brush = Brush.sweepGradient(
                                listOf(BrandAmber.copy(alpha = 0f), BrandAmber, BrandCoral, BrandRose),
                            ),
                            startAngle = 0f,
                            sweepAngle = 300f,
                            useCenter = false,
                            topLeft = Offset(w / 2f, w / 2f),
                            size = Size(size.width - w, size.height - w),
                            style = Stroke(width = w, cap = StrokeCap.Round),
                        )
                    }
                }
                Icon(
                    painter = painterResource(R.drawable.ic_aperture),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** Listening: a sunset-gradient disc with live equalizer bars and outward ripple rings. */
    @Composable
    private fun ListeningOrb(amps: List<Float>, modifier: Modifier = Modifier) {
        val t = rememberInfiniteTransition(label = "orb")
        val ripple by t.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
            label = "ripple",
        )
        val level = amps.takeLast(8).maxOrNull() ?: 0f
        Box(modifier, contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val baseR = size.minDimension * 0.30f

                // Outward ripples — "the breath becomes a sound."
                for (k in 0..1) {
                    val p = (ripple + k * 0.5f) % 1f
                    val r = baseR * (1f + p * 1.7f)
                    drawCircle(
                        color = BrandCoral.copy(alpha = (1f - p) * 0.35f),
                        radius = r,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }

                // Gradient disc, gently breathing with the live level.
                val discR = baseR * (1f + level * 0.12f)
                drawCircle(brush = SunsetBrush, radius = discR, center = center)

                // Cream equalizer bars over the disc.
                val bars = amps.takeLast(9)
                if (bars.isNotEmpty()) {
                    val span = discR * 1.25f
                    val slot = (span * 2f) / bars.size
                    val barW = (slot * 0.5f).coerceAtLeast(2f)
                    bars.forEachIndexed { i, a ->
                        val h = (a * discR * 1.6f).coerceIn(barW, discR * 1.7f)
                        val x = center.x - span + i * slot + slot / 2f
                        drawRoundRect(
                            color = MarkCream,
                            topLeft = Offset(x - barW / 2f, center.y - h / 2f),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                        )
                    }
                }
            }
        }
    }
}
