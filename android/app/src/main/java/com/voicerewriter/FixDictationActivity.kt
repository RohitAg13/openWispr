package com.voicerewriter

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicerewriter.textproc.VocabEntry
import kotlinx.coroutines.launch

/**
 * "Fix a word" learn-from-edits: shown when the user taps the post-dictation
 * notification. Lists the last dictation's words; tapping a wrong one lets the user
 * type the correct spelling, which is saved as a dictionary correction (alias →
 * canonical) so it's right next time.
 */
class FixDictationActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = VocabRepository(applicationContext)
        val text = LastDictation.get(applicationContext)
        setContent {
            com.voicerewriter.ui.OpenWisprTheme {
                Surface(color = MaterialTheme.colorScheme.background) { FixScreen(repo, text) }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FixScreen(repo: VocabRepository, text: String) {
        val scope = rememberCoroutineScope()
        var editing by remember { mutableStateOf<String?>(null) }   // the word being corrected
        var correction by remember { mutableStateOf("") }
        val words = remember(text) { Regex("[\\p{L}\\p{N}'’.@/-]+").findAll(text).map { it.value }.toList() }

        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fix a word", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (words.isEmpty()) {
                Text("No recent dictation to fix.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { finish() }) { Text("Close") }
                return@Column
            }
            Text("Tap the word the recognizer got wrong, then type the correct spelling. " +
                "We'll remember it for next time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                words.distinct().forEach { w ->
                    AssistChip(onClick = { editing = w; correction = w }, label = { Text(w) })
                }
            }
            TextButton(onClick = { finish() }) { Text("Done") }
        }

        val wrong = editing
        if (wrong != null) {
            AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("Correct \"$wrong\"") },
                text = {
                    OutlinedTextField(
                        value = correction, onValueChange = { correction = it },
                        label = { Text("Correct spelling") }, singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val fixed = correction.trim()
                        if (fixed.isNotEmpty() && !fixed.equals(wrong, ignoreCase = true)) {
                            scope.launch {
                                learn(repo, wrong = wrong, right = fixed)
                                Toast.makeText(this@FixDictationActivity, "Learned: $wrong → $fixed", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                        } else editing = null
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            )
        }
    }

    /** Add [wrong] as an alias of [right] (merging with an existing entry if present). */
    private suspend fun learn(repo: VocabRepository, wrong: String, right: String) =
        repo.learnAlias(wrong, right)
}
