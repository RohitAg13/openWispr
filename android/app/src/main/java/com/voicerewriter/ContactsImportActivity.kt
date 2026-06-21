package com.voicerewriter

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.voicerewriter.textproc.VocabEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets the user import names from contacts into the personal dictionary. Contact
 * names are split into individual proper-noun tokens (so "Clootrack Mohit" becomes
 * "Clootrack" + "Mohit") and presented as a checklist — the user picks which to
 * keep so company/role noise doesn't pollute the dictionary.
 */
class ContactsImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VocabRepository(applicationContext)
        setContent {
            com.voicerewriter.ui.OpenWisprTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ImportScreen(repo)
                }
            }
        }
    }

    @Composable
    private fun ImportScreen(repo: VocabRepository) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var candidates by remember { mutableStateOf<List<ContactsImporter.Candidate>?>(null) }
        val checked = remember { mutableStateMapOf<String, Boolean>() }
        var denied by remember { mutableStateOf(false) }

        suspend fun load() {
            val existing = repo.get().map { it.canonical.lowercase() }.toSet()
            candidates = withContext(Dispatchers.IO) { ContactsImporter.candidates(context, existing) }
        }

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted -> if (granted) scope.launch { load() } else denied = true }

        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) load()
            else permLauncher.launch(Manifest.permission.READ_CONTACTS)
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Import from contacts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Names are split into individual words so company/role labels don't get added " +
                    "as one entry. Uncheck anything you don't want.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val list = candidates
            when {
                denied -> Text("Contacts permission denied. Enable it in system settings to import.",
                    color = MaterialTheme.colorScheme.error)
                list == null -> Text("Reading contacts…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.isEmpty() -> Text("No new names found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { list.forEach { checked[it.token] = true } }) { Text("Select all") }
                        TextButton(onClick = { checked.clear() }) { Text("Clear") }
                    }
                    Divider()
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(list, key = { it.token }) { cand ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { checked[cand.token] = !(checked[cand.token] ?: false) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked[cand.token] ?: false,
                                    onCheckedChange = { checked[cand.token] = it },
                                )
                                Text(cand.token, modifier = Modifier.weight(1f))
                                if (cand.count > 1) Text(
                                    "×${cand.count}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    val selectedCount = checked.count { it.value }
                    Button(
                        enabled = selectedCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            scope.launch {
                                val current = repo.get().toMutableList()
                                val have = current.map { it.canonical.lowercase() }.toMutableSet()
                                for ((tok, on) in checked) {
                                    if (on && tok.lowercase() !in have) {
                                        current.add(VocabEntry(tok, source = "contact")); have.add(tok.lowercase())
                                    }
                                }
                                repo.save(current)
                                Toast.makeText(context, "Added $selectedCount to dictionary", Toast.LENGTH_SHORT).show()
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        },
                    ) { Text("Add $selectedCount to dictionary") }
                }
            }
        }
    }
}
