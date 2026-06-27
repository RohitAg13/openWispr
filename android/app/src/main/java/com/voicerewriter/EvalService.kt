package com.voicerewriter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.voicerewriter.textproc.TextProcessingConfig
import com.voicerewriter.textproc.TextProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Tier-3 on-device benchmark runner (foreground service — the Android analog of macOS
 * `BenchDump`). Runs the real pipeline (textproc -> optional gated LLM polish) over the
 * research datasets and dumps `results.jsonl` + `timings.jsonl` + `meta.json` for score.py.
 *
 * Foreground so the LLM-heavy process survives the whole sweep (a plain receiver/goAsync run
 * gets low-memory-killed ~case 32). Files are flushed after every case, so even a killed run
 * leaves partial, scoreable data. Started by [EvalReceiver]. Modes: `polish` (textproc -> LLM,
 * skips the LLM when `gate=true` and the deterministic output is clean per [BenchGate]) and
 * `det` (deterministic only — the gated-clean lower bound).
 */
class EvalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
        val mode = intent?.getStringExtra("mode") ?: "polish"
        val gate = intent?.getBooleanExtra("gate", false) ?: false
        val repeats = (intent?.getIntExtra("repeats", 1) ?: 1).coerceAtLeast(1)
        val base = applicationContext.getExternalFilesDir(null)!!
        val inDir = File(intent?.getStringExtra("in") ?: File(base, "eval-in").path)
        val outDir = File(intent?.getStringExtra("out") ?: File(base, "eval-out").path)
        outDir.mkdirs()

        scope.launch {
            try {
                runEval(mode, gate, repeats, inDir, outDir)
            } catch (t: Throwable) {
                Log.e(TAG, "eval failed", t)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private data class Case(val id: String, val raw: String, val isCode: Boolean)

    private fun loadCases(inDir: File): List<Case> {
        val out = ArrayList<Case>()
        for (name in listOf("cleanup.jsonl", "names_emails.jsonl")) {
            val f = File(inDir, name)
            if (!f.exists()) continue
            f.forEachLine { line ->
                val s = line.trim()
                if (s.isEmpty()) return@forEachLine
                val o = JSONObject(s)
                val dims = o.optJSONArray("dims")
                var isCode = o.optBoolean("is_code", false)
                if (dims != null) for (i in 0 until dims.length()) if (dims.optString(i) == "code") isCode = true
                out.add(Case(o.getString("id"), o.optString("raw"), isCode))
            }
        }
        return out
    }

    private suspend fun runEval(mode: String, gate: Boolean, repeats: Int, inDir: File, outDir: File) {
        val s0 = SettingsRepository(applicationContext).get()
        val settings = s0.copy(
            provider = "local",
            model = LlmModelManager.FINETUNE_MODEL_ID,
            polishLevel = PolishLevel.FULL,
        )
        val cases = loadCases(inDir)
        Log.i(TAG, "start mode=$mode gate=$gate repeats=$repeats cases=${cases.size} model=${settings.model}")

        val results = StringBuilder()
        val timings = StringBuilder()
        var nGated = 0

        for ((ci, c) in cases.withIndex()) {
            val det = TextProcessor.process(c.raw, TextProcessingConfig(), isCodeContext = c.isCode)
            val gated = mode == "polish" && gate && !BenchGate.needsPolish(det)
            if (gated) nGated++
            var output = det
            for (run in 0 until repeats) {
                val t0 = System.nanoTime()
                output = when {
                    mode == "det" -> det
                    gated -> BenchGate.finish(det)
                    else -> {
                        val sb = StringBuilder()
                        LocalLlmEngine.streamWithPrompt(applicationContext, settings, "", det).collect { sb.append(it) }
                        RewriteEngine.cleanOutput(sb.toString()).ifBlank { det }
                    }
                }
                val ms = (System.nanoTime() - t0) / 1_000_000.0
                timings.append(
                    JSONObject(
                        linkedMapOf(
                            "id" to c.id, "run" to run, "cold" to (run == 0),
                            "polish_ms" to round1(ms), "total_ms" to round1(ms), "gated" to gated
                        )
                    )
                ).append('\n')
            }
            results.append(JSONObject(linkedMapOf("id" to c.id, "output" to output))).append('\n')
            File(outDir, "results.jsonl").writeText(results.toString())   // incremental flush
            File(outDir, "timings.jsonl").writeText(timings.toString())
            Log.i(TAG, "[${ci + 1}/${cases.size}] ${c.id} ${if (gated) "GATED" else mode} -> ${output.take(48)}")
        }

        File(outDir, "meta.json").writeText(
            JSONObject(
                linkedMapOf(
                    "mode" to mode, "gate" to gate, "repeats" to repeats,
                    "model" to settings.model, "count" to cases.size, "gated" to nGated
                )
            ).toString()
        )
        Log.i(TAG, "done: ${cases.size} cases, gated $nGated/${cases.size} -> ${outDir.path}")
    }

    private fun round1(x: Double): Double = Math.round(x * 10.0) / 10.0

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Benchmark", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_aperture)
            .setContentTitle("OpenWispr eval")
            .setContentText("Running on-device benchmark…")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "EvalService"
        private const val NOTIF_ID = 4242
        private const val CHANNEL_ID = "eval"
    }
}
