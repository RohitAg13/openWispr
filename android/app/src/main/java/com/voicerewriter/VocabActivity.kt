package com.voicerewriter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicerewriter.textproc.VocabEntry
import kotlinx.coroutines.launch

/**
 * Manages the personal vocabulary: names/terms the recognizer mishears, plus their
 * known mishearings (aliases). [VocabCorrector] snaps near-misses back to these
 * after transcription. Edits persist immediately via [VocabRepository].
 */
class VocabActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VocabRepository(applicationContext)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VocabScreen(repo)
                }
            }
        }
    }

    @Composable
    private fun VocabScreen(repo: VocabRepository) {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val entries = remember { mutableStateListOf<VocabEntry>() }
        var canonical by remember { mutableStateOf("") }
        var aliases by remember { mutableStateOf("") }
        var expansion by remember { mutableStateOf("") }

        suspend fun reload() { entries.clear(); entries.addAll(repo.get()) }
        LaunchedEffect(Unit) { reload() }
        fun persist() = scope.launch { repo.save(entries.toList()) }

        // Reload after the contacts-import screen adds entries.
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { scope.launch { reload() } }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Personal dictionary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Names and terms the recognizer gets wrong. We snap near-misses back to the " +
                    "correct spelling after you speak — fully on-device. Add known mishearings as " +
                    "aliases (comma-separated) for the tricky ones.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = canonical, onValueChange = { canonical = it },
                label = { Text("Word, or phrase to expand") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = aliases, onValueChange = { aliases = it },
                label = { Text("Aliases (optional, comma-separated)") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = expansion, onValueChange = { expansion = it },
                    label = { Text("Expands to (optional snippet, e.g. an email)") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = canonical.isNotBlank(),
                    onClick = {
                        val a = aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        entries.add(0, VocabEntry(canonical.trim(), a, expansion.trim().ifEmpty { null }))
                        canonical = ""; aliases = ""; expansion = ""
                        persist()
                    },
                ) { Icon(Icons.Default.Add, contentDescription = "Add") }
            }

            TextButton(onClick = {
                importLauncher.launch(android.content.Intent(context, ContactsImportActivity::class.java))
            }) { Text("Import from contacts") }

            Divider()

            if (entries.isEmpty()) {
                Text("No words yet.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(entries, key = { it.canonical + it.aliases.hashCode() }) { e ->
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (e.isSnippet) "${e.canonical}  ⇒  ${e.expansion}" else e.canonical,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (e.aliases.isNotEmpty()) Text(
                                    "↳ ${e.aliases.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { entries.remove(e); persist() }) {
                                Icon(Icons.Default.Close, contentDescription = "Delete",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
