package com.voicerewriter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Captures raw 16 kHz mono PCM16 from the mic with AudioRecord. [stop] hands back the
 * take as plain samples; persisting it is [PendingAudio]'s job, and every backend is fed
 * from that durable copy rather than from a buffer that only exists in this process.
 *
 * Requires the RECORD_AUDIO permission (the activity checks before start()).
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16_000 // Whisper is trained at 16 kHz
        private const val SPEECH_START_PROB = 0.5f
        private const val SPEECH_END_PROB = 0.35f

        /**
         * Silence needed before auto-stop fires. Was 0.8s, which is shorter than an ordinary
         * mid-sentence pause ("so... the thing is") and cut people off mid-thought - the main
         * reason auto-stop felt broken on a phone. Tap is now the long-form gesture, so this
         * leans patient; hold-to-talk covers the case where you want to end it instantly.
         */
        private const val HANGOVER_SAMPLES = (1.6 * SAMPLE_RATE).toInt()

        /**
         * Speech that must accumulate before auto-stop can arm at all. Without this, one
         * 32ms frame over the start threshold - a cough, a door, a keyboard click - counted
         * as "they started talking", and the hangover then ended the recording before the
         * user had said a word.
         */
        private const val MIN_SPEECH_SAMPLES = (0.4 * SAMPLE_RATE).toInt()

        private const val PRE_PAD_SAMPLES = (0.2 * SAMPLE_RATE).toInt()
        private const val POST_PAD_SAMPLES = (0.3 * SAMPLE_RATE).toInt()
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var recording = false
    @Volatile private var lastPeak = 0 // most recent buffer peak, for the waveform
    private val chunks = ArrayList<ShortArray>()
    private val mainHandler = Handler(Looper.getMainLooper())

    // VAD state (only used when a Silero model is available + enabled)
    private var vad: SileroVad? = null
    private var totalSamples = 0
    private var speechStarted = false
    private var firstSpeechSample = 0
    private var lastSpeechSample = 0
    private var autoStopFired = false
    private var speechSamples = 0

    val isRecording: Boolean get() = recording

    /**
     * Whether this take will actually auto-stop. False when the setting is off *and* when the
     * Silero model failed to load, which is silent otherwise - the UI used to promise "I'll
     * stop when you pause" either way and then never stop.
     */
    @Volatile var vadActive: Boolean = false
        private set

    /** Current peak amplitude (0..32767) for waveform visualization. */
    fun amplitude(): Int = lastPeak

    /**
     * Start capturing. If [vadAutoStop] and the Silero model loads, [onAutoStop] is
     * invoked (on the main thread) once the speaker has paused, and silence is
     * trimmed from the result.
     */
    @SuppressLint("MissingPermission")
    fun start(vadAutoStop: Boolean = false, onAutoStop: (() -> Unit)? = null) {
        if (recording) return
        chunks.clear()
        lastPeak = 0
        totalSamples = 0
        speechStarted = false
        firstSpeechSample = 0
        lastSpeechSample = 0
        autoStopFired = false
        speechSamples = 0
        vad = if (vadAutoStop) SileroVad.shared(context)?.also { it.reset() } else null
        vadActive = vad != null

        val frame = SileroVad.CHUNK // 512 samples; small reads → responsive VAD
        val minBuf = max(
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            frame * 8,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't initialize the microphone.")
        }
        record = rec
        recording = true
        rec.startRecording()
        worker = Thread {
            val buf = ShortArray(frame)
            val vadInst = vad
            while (recording) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val a = abs(buf[i].toInt())
                    if (a > peak) peak = a
                }
                lastPeak = peak
                synchronized(chunks) { chunks.add(buf.copyOf(n)) }
                totalSamples += n
                if (vadInst != null && n == frame) {
                    val f = FloatArray(frame) { buf[it] / 32768f }
                    val prob = try { vadInst.process(f) } catch (_: Exception) { 0f }
                    updateVad(prob, totalSamples, onAutoStop)
                }
            }
        }.also { it.start() }
    }

    /** Speech-end detection: fire [onAutoStop] once the speaker pauses. */
    private fun updateVad(prob: Float, endSample: Int, onAutoStop: (() -> Unit)?) {
        if (prob >= SPEECH_START_PROB) {
            if (!speechStarted) {
                speechStarted = true
                firstSpeechSample = max(0, endSample - SileroVad.CHUNK - PRE_PAD_SAMPLES)
            }
            lastSpeechSample = endSample
            speechSamples += SileroVad.CHUNK
        }
        // speechSamples, not speechStarted: a single stray frame over the threshold must not be
        // enough to arm the auto-stop. Trimming still keys off speechStarted, so a short blip
        // that never arms this can't lose the audio around it either.
        if (speechSamples >= MIN_SPEECH_SAMPLES && !autoStopFired && prob < SPEECH_END_PROB) {
            if (endSample - lastSpeechSample >= HANGOVER_SAMPLES) {
                autoStopFired = true
                onAutoStop?.let { cb -> mainHandler.post { cb() } }
            }
        }
    }

    /** Stop and return all captured samples (null if nothing usable was recorded). */
    fun stop(): ShortArray? {
        if (!recording && record == null) return null
        recording = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
        record?.let { r ->
            try { r.stop() } catch (_: Exception) {}
            r.release()
        }
        record = null
        val all = synchronized(chunks) { chunks.toList() }
        chunks.clear()
        val total = all.sumOf { it.size }
        if (total < SAMPLE_RATE / 4) return null // < ~0.25s: nothing meaningful
        val full = ShortArray(total)
        var off = 0
        for (c in all) { c.copyInto(full, off); off += c.size }
        // VAD trim: keep just the speech region (with a little padding) — avoids
        // whisper hallucinating on trailing silence.
        if (speechStarted) {
            val start = firstSpeechSample.coerceIn(0, total)
            val end = min(total, lastSpeechSample + POST_PAD_SAMPLES)
            if (end - start >= SAMPLE_RATE / 4) return full.copyOfRange(start, end)
        }
        return full
    }

    fun cancel() {
        stop()
    }
}
