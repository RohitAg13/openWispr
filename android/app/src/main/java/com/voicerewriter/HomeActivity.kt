package com.voicerewriter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.voicerewriter.ui.BrandCoral
import com.voicerewriter.ui.MarkCream
import com.voicerewriter.ui.Mulish
import com.voicerewriter.ui.OpenWisprTheme
import com.voicerewriter.ui.PlexMono
import com.voicerewriter.ui.SunsetBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The app's home: a stat band over a day-grouped feed of recent dictations, with a
 * Material-3 bottom nav (Home · Talk · Settings). Talk opens the existing dictation
 * sheet; Settings opens the existing full settings screen — only Home is new here.
 * Implements the "OpenWispr Mobile" design handoff (Home tab).
 */
class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge() // draw under the status/nav bars; the cream/plum bg fills the whole screen
        super.onCreate(savedInstanceState)
        setContent { OpenWisprTheme { HomeScreen() } }
        // First launch: route once into onboarding (it returns here when finished).
        lifecycleScope.launch {
            val done = withContext(Dispatchers.IO) {
                SettingsRepository(this@HomeActivity).get().hasCompletedOnboarding
            }
            if (!done && !isFinishing) startActivity(OnboardingActivity.intent(this@HomeActivity))
        }
    }

    private data class HomeData(
        val keepHistory: Boolean,
        val groups: List<DayGroup>,
        val stats: DictationStats,
    )

    private data class DayGroup(val label: String, val items: List<DictationEntry>)

    @Composable
    private fun HomeScreen() {
        val ctx = this
        val scope = rememberCoroutineScope()
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        var data by remember { mutableStateOf<HomeData?>(null) }
        var viewBefore by remember { mutableStateOf(setOf<String>()) }
        var teachOpen by remember { mutableStateOf<String?>(null) }
        var editOpen by remember { mutableStateOf<String?>(null) }
        var toast by remember { mutableStateOf<String?>(null) }

        fun reload() {
            scope.launch {
                val d = withContext(Dispatchers.IO) { loadHome(ctx) }
                data = d
            }
        }

        // Reload every time the screen resumes (stats change as you dictate).
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) reload()
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }

        LaunchedEffect(toast) {
            if (toast != null) { kotlinx.coroutines.delay(2200); toast = null }
        }

        fun showToast(m: String) { toast = m }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // Scroll area
                    Column(
                        Modifier.weight(1f).fillMaxWidth().statusBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(18.dp, 14.dp, 18.dp, 28.dp),
                    ) {
                        TopLockup()
                        Spacer(Modifier.height(18.dp))

                        val d = data
                        StatBand(d?.stats)

                        Text(
                            "Recent",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(top = 26.dp, bottom = 12.dp),
                        )

                        when {
                            d == null -> Unit
                            !d.keepHistory -> HistoryOffCard { openSettings() }
                            d.groups.isEmpty() -> EmptyHistoryCard()
                            else -> d.groups.forEach { group ->
                                DayHeader(group.label)
                                group.items.forEach { e ->
                                    EntryCard(
                                        entry = e,
                                        showBefore = viewBefore.contains(e.id),
                                        teachOpen = teachOpen == e.id,
                                        editOpen = editOpen == e.id,
                                        onToggleBefore = { before ->
                                            viewBefore = if (before) viewBefore + e.id else viewBefore - e.id
                                        },
                                        onCopy = {
                                            val txt = if (viewBefore.contains(e.id)) e.before else e.after
                                            copyToClipboard(txt); showToast("Copied to clipboard")
                                        },
                                        onEdit = {
                                            editOpen = if (editOpen == e.id) null else e.id
                                            if (editOpen == e.id) teachOpen = null
                                        },
                                        onSaveEdit = { newText, pairs ->
                                            val old = e.after
                                            if (newText.isNotBlank() && newText != old) {
                                                scope.launch {
                                                    withContext(Dispatchers.IO) {
                                                        DictationHistory.update(ctx, e.id, newText)
                                                        val repo = VocabRepository(ctx)
                                                        pairs.forEach { (from, to) -> runCatching { repo.learnAlias(from, to) } }
                                                        runCatching { repo.learnFromEdit(old, newText) }
                                                        runCatching {
                                                            CorrectionCorpus.record(ctx, CorrectionSample(
                                                                ts = System.currentTimeMillis(), category = "generic",
                                                                cleaned = old, final = newText, edited = true,
                                                            ))
                                                        }
                                                    }
                                                    editOpen = null
                                                    reload(); showToast("Saved & learned")
                                                }
                                            } else {
                                                editOpen = null
                                            }
                                        },
                                        onCloseEdit = { editOpen = null },
                                        onTeach = {
                                            teachOpen = if (teachOpen == e.id) null else e.id
                                            if (teachOpen == e.id) editOpen = null
                                        },
                                        onDelete = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) { DictationHistory.delete(ctx, e.id) }
                                                if (teachOpen == e.id) teachOpen = null
                                                if (editOpen == e.id) editOpen = null
                                                reload(); showToast("Dictation deleted")
                                            }
                                        },
                                        onSaveTeach = { pairs ->
                                            scope.launch {
                                                val valid = pairs.map { it.first.trim() to it.second.trim() }
                                                    .filter { it.first.isNotBlank() && it.second.isNotBlank() }
                                                if (valid.isNotEmpty()) {
                                                    withContext(Dispatchers.IO) {
                                                        val repo = VocabRepository(ctx)
                                                        valid.forEach { (from, to) -> runCatching { repo.learnAlias(from, to) } }
                                                    }
                                                    showToast(if (valid.size == 1) "Correction saved" else "${valid.size} corrections saved")
                                                }
                                                teachOpen = null
                                            }
                                        },
                                        onCloseTeach = { teachOpen = null },
                                    )
                                    Spacer(Modifier.height(11.dp))
                                }
                                Spacer(Modifier.height(13.dp))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    BottomBar(onTalk = ::openTalk, onSettings = ::openSettings)
                }

                // Snackbar
                AnimatedVisibility(
                    visible = toast != null,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            toast ?: "",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp, 13.dp),
                        )
                    }
                }
            }
        }
    }

    // ---------------- pieces ----------------

    @Composable
    private fun TopLockup() {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    painterResource(R.drawable.ic_aperture),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Text(
                    "OpenWispr",
                    fontFamily = Mulish,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 19.sp,
                    letterSpacing = (-0.02).em,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(SunsetBrush))
                Eyebrow("Ready")
            }
        }
    }

    @Composable
    private fun StatBand(stats: DictationStats?) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp, 22.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    StatCell("Words", formatWords(stats?.totalWords ?: 0L), Modifier.weight(1f))
                    StatDivider()
                    StatCell("Saved", formatSaved(stats?.timeSavedMinutes ?: 0.0), Modifier.weight(1f))
                    StatDivider()
                    StatCell("Streak", (stats?.streakDays ?: 0).toString(), Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)))
                Spacer(Modifier.height(15.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    StatChip("Accept", rateLabel(stats?.acceptRate ?: -1))
                    StatChip("On-device", rateLabel(stats?.onDeviceRate ?: -1))
                }
            }
        }
    }

    @Composable
    private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
        Column(modifier) {
            Box(Modifier.width(22.dp).height(3.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(11.dp))
            Text(
                value,
                fontFamily = Mulish,
                fontWeight = FontWeight.SemiBold,
                fontSize = 30.sp,
                letterSpacing = (-0.02).em,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(9.dp))
            Eyebrow(label, size = 9.5f)
        }
    }

    @Composable
    private fun StatDivider() {
        Box(Modifier.padding(horizontal = 16.dp).width(1.dp).height(60.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)))
    }

    @Composable
    private fun StatChip(label: String, value: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp, 7.dp),
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Eyebrow(label, size = 10f)
            Text(value, fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun DayHeader(label: String) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 11.dp),
        ) {
            Eyebrow(label, size = 10f, tracking = 0.14)
            Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)))
        }
    }

    @Composable
    private fun EntryCard(
        entry: DictationEntry,
        showBefore: Boolean,
        teachOpen: Boolean,
        editOpen: Boolean,
        onToggleBefore: (Boolean) -> Unit,
        onCopy: () -> Unit,
        onEdit: () -> Unit,
        onSaveEdit: (String, List<Pair<String, String>>) -> Unit,
        onCloseEdit: () -> Unit,
        onTeach: () -> Unit,
        onDelete: () -> Unit,
        onSaveTeach: (List<Pair<String, String>>) -> Unit,
        onCloseTeach: () -> Unit,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // header: app + time
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(
                            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                entry.appLabel.take(1).uppercase(),
                                fontFamily = PlexMono, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(entry.appLabel, fontFamily = Mulish, fontWeight = FontWeight.SemiBold,
                            fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(clockTime(entry.timestamp), fontFamily = PlexMono, fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // meta chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MetaChip(durationLabel(entry.durationSec))
                    MetaChip("${entry.words} words")
                    if (entry.onDevice) MetaChip("on-device", dot = true)
                }

                // text (before / after) — or the inline editor when editing
                if (editOpen) {
                    EditForm(entry = entry, onSave = onSaveEdit, onCancel = onCloseEdit)
                } else {
                    Text(
                        if (showBefore) entry.before else entry.after,
                        fontFamily = Mulish,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = if (showBefore) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        fontStyle = if (showBefore) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
                    )
                }

                if (teachOpen) TeachForm(onSave = onSaveTeach, onCancel = onCloseTeach)

                // footer: before/after toggle + actions (hidden while the inline editor is open)
                if (!editOpen) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        BeforeAfterToggle(showBefore, onToggleBefore)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActionText("Edit", onClick = onEdit)
                            ActionText("Copy", onClick = onCopy)
                            ActionText("Teach", onClick = onTeach)
                            ActionText("Delete", onClick = onDelete, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun BeforeAfterToggle(showBefore: Boolean, onToggle: (Boolean) -> Unit) {
        Row(
            Modifier.clip(RoundedCornerShape(9.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp),
        ) {
            SegItem("Before", selected = showBefore) { onToggle(true) }
            SegItem("After", selected = !showBefore) { onToggle(false) }
        }
    }

    @Composable
    private fun SegItem(label: String, selected: Boolean, onClick: () -> Unit) {
        Box(
            Modifier.clip(RoundedCornerShape(7.dp))
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(11.dp, 5.dp),
        ) {
            Text(
                label,
                fontFamily = Mulish,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 12.sp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun ActionText(label: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(
            label,
            fontFamily = Mulish,
            fontWeight = FontWeight.Medium,
            fontSize = 12.5.sp,
            color = color,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(8.dp, 6.dp),
        )
    }

    @Composable
    private fun TeachForm(onSave: (List<Pair<String, String>>) -> Unit, onCancel: () -> Unit) {
        // One or more corrections in a single pass — "When I say X, write Y" rows you can stack
        // before saving, so fixing several mis-heard words doesn't mean reopening the form N times.
        var pairs by remember { mutableStateOf(listOf("" to "")) }
        fun setFrom(i: Int, v: String) { pairs = pairs.toMutableList().also { it[i] = v to it[i].second } }
        fun setTo(i: Int, v: String) { pairs = pairs.toMutableList().also { it[i] = it[i].first to v } }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(11.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Eyebrow(if (pairs.size > 1) "Teach corrections" else "Teach a correction", size = 10f, tracking = 0.1)
                pairs.forEachIndexed { i, (from, to) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
                    TeachField("When I say", from, { setFrom(i, it) }, "distil")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { TeachField("Write", to, { setTo(i, it) }, "DistilWhisper") }
                        if (pairs.size > 1) {
                            Text("Remove", fontFamily = Mulish, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                    .clickable { pairs = pairs.filterIndexed { idx, _ -> idx != i }.ifEmpty { listOf("" to "") } }
                                    .padding(8.dp, 18.dp))
                        }
                    }
                }
                Text("+ Add another", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { pairs = pairs + ("" to "") }.padding(6.dp, 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary)
                            .clickable { onSave(pairs) }.padding(18.dp, 9.dp),
                    ) {
                        Text("Save", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text("Cancel", fontFamily = Mulish, fontWeight = FontWeight.Medium, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onCancel).padding(14.dp, 9.dp))
                }
            }
        }
    }

    /**
     * Inline editor for a past dictation's kept text. Primary mode renders the sentence
     * with each word individually tappable — tap a mis-transcribed word and fix just that
     * word in place (no retyping the rest). A "full text" fallback exposes the whole string
     * for bigger changes. On save the caller updates the stored entry and teaches the fix.
     */
    @Composable
    private fun EditForm(
        entry: DictationEntry,
        onSave: (String, List<Pair<String, String>>) -> Unit,
        onCancel: () -> Unit,
    ) {
        val cs = MaterialTheme.colorScheme
        val wordRe = remember { Regex("[\\p{L}\\p{N}'’.@/-]+") }
        var working by remember(entry.id) { mutableStateOf(entry.after) }
        var fullText by remember(entry.id) { mutableStateOf(false) }
        var editIdx by remember(entry.id) { mutableStateOf<Int?>(null) }
        var draft by remember(entry.id) { mutableStateOf("") }
        val pairs = remember(entry.id) { mutableStateListOf<Pair<String, String>>() }

        val tokens = remember(working) { wordRe.findAll(working).toList() }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(11.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Eyebrow(if (fullText) "Edit full text" else "Tap a word to fix it", size = 10f, tracking = 0.1)

                if (fullText) {
                    Surface(
                        color = cs.surface, shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.padding(11.dp, 9.dp)) {
                            BasicTextField(
                                value = working, onValueChange = { working = it },
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontFamily = Mulish, fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurface,
                                ),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val annotated = buildAnnotatedString {
                        append(working)
                        editIdx?.let { i ->
                            tokens.getOrNull(i)?.let { m ->
                                addStyle(SpanStyle(color = cs.primary, fontWeight = FontWeight.Bold), m.range.first, m.range.last + 1)
                            }
                        }
                    }
                    Text(
                        annotated,
                        fontFamily = Mulish, fontSize = 15.sp, lineHeight = 22.sp, color = cs.onSurface,
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth().pointerInput(tokens) {
                            detectTapGestures { pos ->
                                val lr = layout ?: return@detectTapGestures
                                val off = lr.getOffsetForPosition(pos)
                                val idx = tokens.indexOfFirst { off >= it.range.first && off <= it.range.last + 1 }
                                if (idx >= 0) { editIdx = idx; draft = tokens[idx].value }
                            }
                        },
                    )

                    if (editIdx != null) {
                        val original = tokens.getOrNull(editIdx!!)?.value ?: ""
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Fix “$original”", fontFamily = Mulish, fontSize = 11.sp, color = cs.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(
                                    color = cs.surface, shape = RoundedCornerShape(8.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, cs.outline),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Box(Modifier.padding(11.dp, 9.dp)) {
                                        BasicTextField(
                                            value = draft, onValueChange = { draft = it }, singleLine = true,
                                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                                fontFamily = Mulish, fontSize = 14.sp, color = cs.onSurface,
                                            ),
                                            cursorBrush = SolidColor(cs.primary),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                }
                                Text(
                                    "Set", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = cs.primary,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                        val i = editIdx
                                        val m = i?.let { tokens.getOrNull(it) }
                                        val fixed = draft.trim()
                                        if (m != null && fixed.isNotEmpty() && !fixed.equals(m.value, ignoreCase = true)) {
                                            pairs.add(m.value to fixed)
                                            working = working.replaceRange(m.range, fixed)
                                        }
                                        editIdx = null
                                    }.padding(10.dp, 8.dp),
                                )
                                Text(
                                    "Cancel", fontFamily = Mulish, fontSize = 13.sp, color = cs.onSurfaceVariant,
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { editIdx = null }.padding(8.dp, 8.dp),
                                )
                            }
                        }
                    }
                }

                Text(
                    if (fullText) "Fix words" else "Edit full text",
                    fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = cs.primary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { fullText = !fullText; editIdx = null }.padding(6.dp, 4.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(20.dp)).background(cs.primary)
                            .clickable { onSave(working, pairs.toList()) }.padding(18.dp, 9.dp),
                    ) {
                        Text("Save", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = cs.onPrimary)
                    }
                    Text(
                        "Cancel", fontFamily = Mulish, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = cs.onSurfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onCancel).padding(14.dp, 9.dp),
                    )
                }
            }
        }
    }

    @Composable
    private fun TeachField(label: String, value: String, onChange: (String) -> Unit, placeholder: String) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, fontFamily = Mulish, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.padding(11.dp, 9.dp)) {
                    if (value.isEmpty()) {
                        Text(placeholder, fontFamily = Mulish, fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = Mulish, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    @Composable
    private fun MetaChip(label: String, dot: Boolean = false) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp, 3.dp),
        ) {
            if (dot) Box(Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Text(label, fontFamily = PlexMono, fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun HistoryOffCard(onOpen: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(SunsetBrush), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_aperture), null, tint = MarkCream, modifier = Modifier.size(26.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("History is off", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface)
                    Text("Nothing is saved to disk. Tap to turn it on in Settings.",
                        fontFamily = Mulish, fontSize = 12.5.sp, lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    @Composable
    private fun EmptyHistoryCard() {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("No history yet", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface)
                Text("Your dictations will appear here.", fontFamily = Mulish, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun BottomBar(onTalk: () -> Unit, onSettings: () -> Unit) {
        // Light tap feedback so the carousel feels physical. VIRTUAL_KEY is the standard
        // "button press" haptic and respects the system's touch-feedback setting.
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
                    // Home (active)
                    NavItem(label = "Home", selected = true, onClick = { haptic() }) {
                        Icon(painterResource(R.drawable.ic_aperture), null,
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                    }
                    // Talk (raised FAB)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.offset(y = (-18).dp).size(58.dp).clip(CircleShape).background(SunsetBrush)
                                .clickable { haptic(); onTalk() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(painterResource(R.drawable.ic_aperture), null, tint = MarkCream,
                                modifier = Modifier.size(30.dp))
                        }
                        Text("Talk", fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.offset(y = (-12).dp))
                    }
                    // Settings
                    NavItem(label = "Settings", selected = false, onClick = { haptic(); onSettings() }) {
                        Icon(painterResource(R.drawable.ic_settings_lines), null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    }
                }
        }
    }

    @Composable
    private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.width(88.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)) {
            Box(
                Modifier.width(64.dp).height(32.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(label, fontFamily = Mulish, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    private fun Eyebrow(text: String, size: Float = 11f, tracking: Double = 0.1) {
        Text(
            text.uppercase(),
            fontFamily = PlexMono,
            fontSize = size.sp,
            letterSpacing = tracking.em,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ---------------- nav ----------------

    private fun openTalk() {
        startActivity(
            Intent(this, RewriteActivity::class.java)
                .putExtra(RewriteActivity.EXTRA_MODE, Defaults.MODE_DICTATE)
                .putExtra(RewriteActivity.EXTRA_AUTO_RECORD, true),
        )
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun copyToClipboard(text: String) {
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("dictation", text))
    }

    // ---------------- data + formatting ----------------

    private fun loadHome(ctx: Context): HomeData {
        val keep = DictationHistory.keepHistory(ctx)
        val entries = if (keep) DictationHistory.all(ctx) else emptyList()
        val groups = LinkedHashMap<String, MutableList<DictationEntry>>()
        for (e in entries) {
            groups.getOrPut(dayLabel(e.timestamp)) { mutableListOf() }.add(e)
        }
        return HomeData(keep, groups.map { DayGroup(it.key, it.value) }, DictationHistory.stats(ctx))
    }

    private fun formatWords(n: Long): String = "%,d".format(n)

    private fun formatSaved(minutes: Double): String {
        if (minutes < 1) return "0m"
        return if (minutes >= 60) "%.1fh".format(minutes / 60.0) else "${minutes.toInt()}m"
    }

    private fun rateLabel(pct: Int): String = if (pct < 0) "—" else "$pct%"

    private fun durationLabel(sec: Int): String = "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"

    private fun clockTime(ts: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ts))

    private fun dayLabel(ts: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        fun ymd(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        val diff = ymd(now) - ymd(then)
        return when {
            diff == 0 -> "Today"
            diff == 1 -> "Yesterday"
            diff in 2..6 -> SimpleDateFormat("EEEE", Locale.US).format(Date(ts))
            else -> SimpleDateFormat("EEEE · MMM d", Locale.US).format(Date(ts))
        }
    }
}
