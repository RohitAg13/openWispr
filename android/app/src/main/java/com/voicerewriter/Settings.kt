package com.voicerewriter

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * How hard the LLM polish edits, replacing the old on/off toggle. Mirrors the cleanup pipeline's
 * CleanupLevel — the `instruction` is appended to the dictation polish prompt. OFF skips
 * the model entirely (deterministic-only). The fine-tune ignores the level (it runs its
 * trained behavior); levels steer the cloud / generic on-device models.
 */
enum class PolishLevel(val key: String, val label: String, val blurb: String, val instruction: String) {
    OFF("off", "Off", "Deterministic cleanup only — no model. Fast and safe.", ""),
    LIGHT("light", "Light", "Model fixes only capitalization, spacing and punctuation.",
        "Only fix capitalization, spacing and punctuation. Keep all words the same."),
    MEDIUM("medium", "Medium", "Also splits run-on sentences and fixes small grammar.",
        "You may split run-on sentences and fix small grammar mistakes. " +
            "If the speaker corrects a previous word or number, keep only the corrected version."),
    FULL("full", "Full", "Fuller cleanup, with one small rewrite for clarity if needed.",
        "You may split run-on sentences and fix grammar, and use one small rewrite only if " +
            "needed for clarity. If the speaker corrects a previous word or number, keep only " +
            "the corrected version.");

    companion object {
        fun from(key: String?): PolishLevel = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Port of DEFAULT_SETTINGS from defaults.js. */
data class Settings(
    val provider: String = Defaults.DEFAULT_PROVIDER,
    val model: String = Defaults.DEFAULT_MODEL,
    val customEndpoint: String = "",
    val apiKey: String = "",
    val voice: String = "",
    val antiAI: Boolean = true,
    val temperature: Double = Defaults.DEFAULT_TEMPERATURE,
    // --- Speech-to-text (Whisper) ---
    val sttProvider: String = Defaults.DEFAULT_STT_PROVIDER,
    val sttEndpoint: String = "", // only used when sttProvider == "custom"
    val sttKey: String = "",
    val sttModel: String = "",
    // --- OpenWispr behavior ---
    val defaultMode: String = Defaults.MODE_DICTATE, // "dictate" | "rewrite"
    val deterministicCleanup: Boolean = true, // fast rule-based cleanup (fillers, spoken forms, numbers, self-corrections)
    val polishLevel: PolishLevel = PolishLevel.OFF, // LLM polish intensity (replaces the old on/off toggle)
    val vadAutoStop: Boolean = true, // Silero VAD: auto-stop when the speaker pauses
    val bubbleOnlyOnFields: Boolean = true, // show the bubble only while a text field is focused (needs accessibility)
) {
    /**
     * Whether to run the LLM polish layer on dictation. Off by default — the deterministic
     * pipeline handles the cleanup; the model is opt-in via [polishLevel] (tiny on-device
     * models tend to over-edit, so users dial the intensity).
     */
    val llmPolishEnabled: Boolean
        get() = polishLevel != PolishLevel.OFF
    /** Mirrors the extension's gate: needs a key before it can rewrite. */
    val isConfigured: Boolean get() = apiKey.isNotBlank()

    /** Voice features need a speech-to-text key as well as the LLM key. */
    val isSttConfigured: Boolean get() = sttKey.isNotBlank()

    /** Resolved STT endpoint (custom override, else the provider default). */
    val sttEndpointResolved: String
        get() = if (sttProvider == "custom") sttEndpoint.trim()
                else Defaults.STT_PROVIDERS[sttProvider]?.endpoint.orEmpty()

    /** Resolved STT model (explicit override, else the provider default). */
    val sttModelResolved: String
        get() = sttModel.trim().ifEmpty {
            Defaults.STT_PROVIDERS[sttProvider]?.defaultModel.orEmpty()
        }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val MODEL = stringPreferencesKey("model")
        val CUSTOM_ENDPOINT = stringPreferencesKey("customEndpoint")
        val API_KEY = stringPreferencesKey("apiKey")
        val VOICE = stringPreferencesKey("voice")
        val ANTI_AI = booleanPreferencesKey("antiAI")
        val TEMPERATURE = doublePreferencesKey("temperature")
        val STT_PROVIDER = stringPreferencesKey("sttProvider")
        val STT_ENDPOINT = stringPreferencesKey("sttEndpoint")
        val STT_KEY = stringPreferencesKey("sttKey")
        val STT_MODEL = stringPreferencesKey("sttModel")
        val DEFAULT_MODE = stringPreferencesKey("defaultMode")
        val DETERMINISTIC_CLEANUP = booleanPreferencesKey("deterministicCleanup")
        val POLISH_LEVEL = stringPreferencesKey("polishLevel")
        val VAD_AUTO_STOP = booleanPreferencesKey("vadAutoStop")
        val BUBBLE_ONLY_ON_FIELDS = booleanPreferencesKey("bubbleOnlyOnFields")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            provider = p[Keys.PROVIDER] ?: defaults.provider,
            model = p[Keys.MODEL] ?: defaults.model,
            customEndpoint = p[Keys.CUSTOM_ENDPOINT] ?: defaults.customEndpoint,
            apiKey = p[Keys.API_KEY] ?: defaults.apiKey,
            voice = p[Keys.VOICE] ?: defaults.voice,
            antiAI = p[Keys.ANTI_AI] ?: defaults.antiAI,
            temperature = p[Keys.TEMPERATURE] ?: defaults.temperature,
            sttProvider = p[Keys.STT_PROVIDER] ?: defaults.sttProvider,
            sttEndpoint = p[Keys.STT_ENDPOINT] ?: defaults.sttEndpoint,
            sttKey = p[Keys.STT_KEY] ?: defaults.sttKey,
            sttModel = p[Keys.STT_MODEL] ?: defaults.sttModel,
            defaultMode = p[Keys.DEFAULT_MODE] ?: defaults.defaultMode,
            deterministicCleanup = p[Keys.DETERMINISTIC_CLEANUP] ?: defaults.deterministicCleanup,
            polishLevel = PolishLevel.from(p[Keys.POLISH_LEVEL]),
            vadAutoStop = p[Keys.VAD_AUTO_STOP] ?: defaults.vadAutoStop,
            bubbleOnlyOnFields = p[Keys.BUBBLE_ONLY_ON_FIELDS] ?: defaults.bubbleOnlyOnFields,
        )
    }

    suspend fun get(): Settings = settings.first()

    suspend fun save(s: Settings) {
        context.dataStore.edit { p ->
            p[Keys.PROVIDER] = s.provider
            p[Keys.MODEL] = s.model
            p[Keys.CUSTOM_ENDPOINT] = s.customEndpoint
            p[Keys.API_KEY] = s.apiKey
            p[Keys.VOICE] = s.voice
            p[Keys.ANTI_AI] = s.antiAI
            p[Keys.TEMPERATURE] = s.temperature
            p[Keys.STT_PROVIDER] = s.sttProvider
            p[Keys.STT_ENDPOINT] = s.sttEndpoint
            p[Keys.STT_KEY] = s.sttKey
            p[Keys.STT_MODEL] = s.sttModel
            p[Keys.DEFAULT_MODE] = s.defaultMode
            p[Keys.DETERMINISTIC_CLEANUP] = s.deterministicCleanup
            p[Keys.POLISH_LEVEL] = s.polishLevel.key
            p[Keys.VAD_AUTO_STOP] = s.vadAutoStop
            p[Keys.BUBBLE_ONLY_ON_FIELDS] = s.bubbleOnlyOnFields
        }
    }
}
