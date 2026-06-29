package com.voicerewriter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Tier-3 on-device benchmark trigger (adb-driven). Forwards the run parameters to the
 * foreground [EvalService], which runs the real dictation pipeline over the research
 * datasets and dumps results+timings for `bench/score.py`.
 *
 *   adb shell am broadcast -a com.voicerewriter.RUN_EVAL_DUMP -n com.voicerewriter/.EvalReceiver \
 *     --es mode polish --ez gate true --ei repeats 1
 *
 * A plain receiver/goAsync coroutine loses process priority after the broadcast window and the
 * LLM-heavy process gets low-memory-killed mid-run (observed ~case 32). A foreground service
 * keeps the process alive for the whole sweep, so the work is handed off to [EvalService].
 */
class EvalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, EvalService::class.java).apply {
            putExtra("mode", intent.getStringExtra("mode") ?: "polish")
            putExtra("gate", intent.getBooleanExtra("gate", false))
            putExtra("repeats", intent.getIntExtra("repeats", 1))
            putExtra("engine", intent.getStringExtra("engine") ?: "cpu") // "cpu" | "gpu" (MLC/Adreno)
            putExtra("stt", intent.getStringExtra("stt") ?: "") // e2e: "tiny"|"base"|"small"; blank = settings
            putExtra("threads", intent.getIntExtra("threads", 0)) // e2e: whisper thread count; 0 = default
            intent.getStringExtra("in")?.let { putExtra("in", it) }
            intent.getStringExtra("out")?.let { putExtra("out", it) }
        }
        ContextCompat.startForegroundService(context, svc)
    }
}
