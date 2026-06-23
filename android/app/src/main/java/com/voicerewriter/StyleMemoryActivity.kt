package com.voicerewriter

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Style memory" — the on-device correction corpus ([CorrectionCorpus]) that powers
 * few-shot polish and the fine-tune export. Lets the user see what OpenWispr has learned
 * about how they like their dictation, export it for offline training (L4), or clear it.
 */
class StyleMemoryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            com.voicerewriter.ui.OpenWisprTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StyleMemoryScreen()
                }
            }
        }
    }

    /** Write the fine-tune JSONL to the external files dir and offer to share it. */
    private fun export(): Int {
        val jsonl = CorrectionCorpus.exportJsonl(applicationContext)
        if (jsonl.isBlank()) return 0
        val count = jsonl.count { it == '\n' } + 1
        runCatching {
            val base = getExternalFilesDir(null) ?: return@runCatching
            File(base, "corpus").apply { mkdirs() }
                .let { File(it, "finetune.jsonl").writeText(jsonl) }
        }
        runCatching {
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/jsonl"
                putExtra(Intent.EXTRA_SUBJECT, "OpenWispr fine-tune data ($count samples)")
                putExtra(Intent.EXTRA_TEXT, jsonl)
            }
            startActivity(Intent.createChooser(share, "Export style memory"))
        }
        return count
    }

    @Composable
    private fun StyleMemoryScreen() {
        val scope = rememberCoroutineScope()
        val samples = remember { mutableStateListOf<CorrectionSample>() }
        var loaded by remember { mutableStateOf(false) }

        suspend fun reload() {
            val all = withContext(Dispatchers.IO) { CorrectionCorpus.all(applicationContext) }
            samples.clear(); samples.addAll(all); loaded = true
        }
        LaunchedEffect(Unit) { reload() }

        val edited = samples.count { it.edited && it.cleaned != it.final }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Style memory", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Accepted dictations OpenWispr keeps on-device to learn your style. The ones you " +
                    "corrected ($edited) teach the model how you like text cleaned, and can be exported " +
                    "to train a better model later. Nothing here leaves your phone unless you export it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    val n = export()
                    Toast.makeText(this@StyleMemoryActivity,
                        if (n == 0) "No corrections to export yet" else "Exported $n corrected samples",
                        Toast.LENGTH_SHORT).show()
                }) { Text("Export") }
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { CorrectionCorpus.clear(applicationContext) }
                        reload()
                        Toast.makeText(this@StyleMemoryActivity, "Style memory cleared", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Clear all") }
            }

            Divider()

            if (loaded && samples.isEmpty()) {
                Text("Nothing yet — your accepted dictations will appear here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(samples, key = { it.ts.toString() + it.final.hashCode() }) { s ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                (if (s.edited && s.cleaned != s.final) "CORRECTED · " else "KEPT · ") + s.category.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
                            )
                            if (s.edited && s.cleaned != s.final) {
                                Text(s.cleaned, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(s.final, style = MaterialTheme.typography.bodyLarge)
                            } else {
                                Text(s.final, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
