package com.voicerewriter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parakeet (NVIDIA TDT 0.6B) is English-only. The failure this guards against is silent: pick
 * Hindi, keep Parakeet, and you get an English-only transducer's best guess at Devanagari
 * speech with no error anywhere. The routing decision lives in one pure function so every call
 * site agrees; these pin it.
 */
class OnDeviceSttLanguageTest {

    private val parakeet = ParakeetModelManager.MODEL_ID

    @Test fun `english keeps parakeet, which is the recommended default`() {
        assertEquals(parakeet, OnDeviceStt.resolveModel(parakeet, "en"))
    }

    @Test fun `hindi routes off parakeet, because parakeet has no hindi`() {
        val id = OnDeviceStt.resolveModel(parakeet, "hi")
        assertTrue("must not stay on an English-only engine", !OnDeviceStt.isParakeet(id))
        assertTrue(WhisperModelManager.MODELS.any { it.id == id })
    }

    @Test fun `a whisper model is left alone in any language`() {
        for (lang in listOf("en", "hi", "ta")) {
            assertEquals("small", OnDeviceStt.resolveModel("small", lang))
        }
    }

    @Test fun `an unknown model id still falls back to the local default`() {
        assertEquals(
            Defaults.STT_PROVIDERS.getValue("local").defaultModel,
            OnDeviceStt.resolveModel("no-such-model", "en"),
        )
    }

    @Test fun `an unknown model plus a non-english language lands on whisper, not the default`() {
        // The default is Parakeet, so the fallback and the language rule must compose in the
        // right order — resolve the id first, then apply the English-only constraint.
        val id = OnDeviceStt.resolveModel("no-such-model", "hi")
        assertTrue(!OnDeviceStt.isParakeet(id))
    }

    @Test fun `an unrecognized language code is treated as english and keeps parakeet`() {
        // normalize() coerces junk to "en"; routing must agree rather than stranding the user
        // on Whisper because of a bad preference write.
        assertEquals(parakeet, OnDeviceStt.resolveModel(parakeet, "klingon"))
    }
}
