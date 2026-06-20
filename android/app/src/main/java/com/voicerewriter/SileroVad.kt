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
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) {
    companion object {
        const val CHUNK = 512 // samples per frame at 16 kHz (~32 ms)
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

    fun reset() {
        state = FloatArray(2 * 128)
    }

    /** Speech probability for [chunk] (exactly [CHUNK] samples, normalized -1..1). */
    fun process(chunk: FloatArray): Float {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, CHUNK.toLong()))
        val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
        val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(SAMPLE_RATE)), longArrayOf())
        try {
            session.run(mapOf("input" to input, "state" to st, "sr" to sr)).use { out ->
                val prob = (out[0].value as Array<FloatArray>)[0][0]
                @Suppress("UNCHECKED_CAST")
                val ns = out[1].value as Array<Array<FloatArray>> // [2][1][128]
                var k = 0
                for (a in 0 until 2) for (c in 0 until 128) state[k++] = ns[a][0][c]
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
