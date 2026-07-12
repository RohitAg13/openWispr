package com.voicerewriter

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicerewriter.textproc.AppContext
import com.voicerewriter.ui.OpenWisprTheme
import kotlinx.coroutines.launch

/**
 * Edit the per-app-category tone used by the LLM-polish step (when "Polish with
 * LLM" is on): formal in email/docs, casual in chat, etc. Defaults are prefilled;
 * the user can replace them.
 */
class AppToneActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = AppToneRepository(applicationContext)
        setContent {
            OpenWisprTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ToneScreen(repo) }
            }
        }
    }

    private val categories = listOf(
        AppContext.Category.EMAIL, AppContext.Category.CHAT,
        AppContext.Category.SOCIAL, AppContext.Category.NOTES,
    )

    @Composable
    private fun ToneScreen(repo: AppToneRepository) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var loaded by remember { mutableStateOf(false) }
        val values = remember { mutableStateMapOf<String, String>() }

        LaunchedEffect(Unit) {
            val ov = repo.overrides()
            for (c in categories) values[c.key] = ov[c.key] ?: AppContext.DEFAULT_TONE[c].orEmpty()
            loaded = true
        }
        if (!loaded) return

        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Tone by app", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "How dictation is polished in each kind of app — used only when “Polish with LLM” is on. " +
                    "Leave blank for no tone change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (c in categories) {
                Text(c.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = values[c.key].orEmpty(),
                    onValueChange = { values[c.key] = it },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        repo.save(values.toMap())
                        Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
