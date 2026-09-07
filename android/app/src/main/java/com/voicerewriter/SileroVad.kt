package com.voicerewriter

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 over ONNX Runtime. Feed fixed [CHUNK]-sample 16 kHz frames and get
 * a speech probability (0..1). The model is stateful (LSTM + context), so call
 * [reset] at the start of each utterance. Best-effort: [createOrNull] returns null
 * if the model/runtime is unavailable, and callers simply skip VAD.
 *
 * **The graph wants [CONTEXT] + [CHUNK] samples, not [CHUNK].** Silero v5 keeps a 64-sample
 * overlap between windows, and the exported ONNX does *not* maintain it — the reference Python
 * wrapper concatenates the previous window's tail onto the current one before every call. Feed
 * a bare 512 and the input dimension is dynamic, so nothing errors: the model simply returns
 * ~0.001 for every frame, including obvious speech, and any auto-stop built on it can never
 * fire at any threshold. Verified against this exact asset off-device: clear speech scores
 * max 0.032 without the context and 1.000 with it.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    companion object {
        const val CHUNK = 512 // samples per frame at 16 kHz (~32 ms)

        /** Overlap the graph expects in front of each window. Silero v5: 64 at 16 kHz. */
        private const val CONTEXT = 64
        private const val SAMPLE_RATE = 16_000L

        @Volatile private var shared: SileroVad? = null

        /** Process-wide cached instance (the model loads once); null if unavailable. */
        fun shared(context: Context): SileroVad? {
            shared?.let { return it }
            synchronized(this) {
                shared?.let { return it }
                shared = createOrNull(context)
                return shared
            }
        }

        private fun createOrNull(context: Context): SileroVad? = try {
            val bytes = context.assets.open("silero_vad.onnx").use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(bytes, OrtSession.SessionOptions())
            Log.i("SileroVad", "loaded; inputs=${session.inputNames} outputs=${session.outputNames}")
            SileroVad(env, session)
        } catch (e: Exception) {
            Log.e("SileroVad", "failed to load Silero VAD", e)
            null
        }
    }

    private var state = FloatArray(2 * 128)

    /** Tail of the previous window, prepended to the next one. Zeroed for the first frame. */
    private var context = FloatArray(CONTEXT)

    /** Scratch for the concatenated [CONTEXT] + [CHUNK] input, reused across frames. */
    private val inputBuf = FloatArray(CONTEXT + CHUNK)

    fun reset() {
        state = FloatArray(2 * 128)
        context = FloatArray(CONTEXT)
    }

    /** Speech probability for [chunk] (exactly [CHUNK] samples, normalized -1..1). */
    fun process(chunk: FloatArray): Float {
        context.copyInto(inputBuf, 0)
        chunk.copyInto(inputBuf, CONTEXT)
        val input = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(inputBuf), longArrayOf(1, (CONTEXT + CHUNK).toLong()),
        )
        val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf())
        try {
            session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { out ->
                val prob = (out[0].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val ns = out[1].value as Array<Array<FloatArray>> // [2][1][128]
                var k = 0
                for (a in 0 until 2) for (c in 0 until 128) state[k++] = ns[a][0][c]
                // Carry this window's tail into the next call, the way the reference wrapper does.
                chunk.copyInto(context, 0, CHUNK - CONTEXT, CHUNK)
                return prob
            }
        } finally {
            input.close(); st.close(); sr.close()
        }
    }

    fun close() {
        try { session.close() } catch (_: Exception) {}
    }
}
