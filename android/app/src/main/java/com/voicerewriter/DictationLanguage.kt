package com.voicerewriter

/**
 * The dictation language handed to whisper.cpp.
 *
 * Until now `params.language` was hardcoded to "en" in the JNI, so a Hindi (or any non-English)
 * dictation was decoded *as English* — Whisper obediently produced romanised mush or invented
 * plausible English. The models were never the problem: every ggml build we ship is the
 * multilingual one. The setting simply wasn't reachable.
 *
 * The list is generated from whisper.cpp's own `g_lang` table in
 * `lib/src/main/jni/whispercpp/src/whisper.cpp`, so it cannot claim a language the vendored
 * decoder would reject. English is pinned first because it is the default and the overwhelmingly
 * common choice; the rest are alphabetical.
 */
object DictationLanguage {

    data class Lang(val code: String, val label: String)

    const val DEFAULT = "en"

    /** True for the default. Post-processing is English-shaped — see [Settings.deterministicCleanup]. */
    fun isEnglish(code: String): Boolean = normalize(code) == DEFAULT

    /** Coerce a stored value to a language whisper.cpp actually knows, falling back to English. */
    fun normalize(code: String?): String {
        val c = code?.trim()?.lowercase().orEmpty()
        return if (ALL.any { it.code == c }) c else DEFAULT
    }

    fun label(code: String): String =
        ALL.firstOrNull { it.code == normalize(code) }?.label ?: "English"

    val ALL: List<Lang> = listOf(
        Lang("en", "English"),
        Lang("af", "Afrikaans"),
        Lang("sq", "Albanian"),
        Lang("am", "Amharic"),
        Lang("ar", "Arabic"),
        Lang("hy", "Armenian"),
        Lang("as", "Assamese"),
        Lang("az", "Azerbaijani"),
        Lang("ba", "Bashkir"),
        Lang("eu", "Basque"),
        Lang("be", "Belarusian"),
        Lang("bn", "Bengali"),
        Lang("bs", "Bosnian"),
        Lang("br", "Breton"),
        Lang("bg", "Bulgarian"),
        Lang("yue", "Cantonese"),
        Lang("ca", "Catalan"),
        Lang("zh", "Chinese"),
        Lang("hr", "Croatian"),
        Lang("cs", "Czech"),
        Lang("da", "Danish"),
        Lang("nl", "Dutch"),
        Lang("et", "Estonian"),
        Lang("fo", "Faroese"),
        Lang("fi", "Finnish"),
        Lang("fr", "French"),
        Lang("gl", "Galician"),
        Lang("ka", "Georgian"),
        Lang("de", "German"),
        Lang("el", "Greek"),
        Lang("gu", "Gujarati"),
        Lang("ht", "Haitian Creole"),
        Lang("ha", "Hausa"),
        Lang("haw", "Hawaiian"),
        Lang("he", "Hebrew"),
        Lang("hi", "Hindi"),
        Lang("hu", "Hungarian"),
        Lang("is", "Icelandic"),
        Lang("id", "Indonesian"),
        Lang("it", "Italian"),
        Lang("ja", "Japanese"),
        Lang("jw", "Javanese"),
        Lang("kn", "Kannada"),
        Lang("kk", "Kazakh"),
        Lang("km", "Khmer"),
        Lang("ko", "Korean"),
        Lang("lo", "Lao"),
        Lang("la", "Latin"),
        Lang("lv", "Latvian"),
        Lang("ln", "Lingala"),
        Lang("lt", "Lithuanian"),
        Lang("lb", "Luxembourgish"),
        Lang("mk", "Macedonian"),
        Lang("mg", "Malagasy"),
        Lang("ms", "Malay"),
        Lang("ml", "Malayalam"),
        Lang("mt", "Maltese"),
        Lang("mi", "Maori"),
        Lang("mr", "Marathi"),
        Lang("mn", "Mongolian"),
        Lang("my", "Myanmar"),
        Lang("ne", "Nepali"),
        Lang("no", "Norwegian"),
        Lang("nn", "Nynorsk"),
        Lang("oc", "Occitan"),
        Lang("ps", "Pashto"),
        Lang("fa", "Persian"),
        Lang("pl", "Polish"),
        Lang("pt", "Portuguese"),
        Lang("pa", "Punjabi"),
        Lang("ro", "Romanian"),
        Lang("ru", "Russian"),
        Lang("sa", "Sanskrit"),
        Lang("sr", "Serbian"),
        Lang("sn", "Shona"),
        Lang("sd", "Sindhi"),
        Lang("si", "Sinhala"),
        Lang("sk", "Slovak"),
        Lang("sl", "Slovenian"),
        Lang("so", "Somali"),
        Lang("es", "Spanish"),
        Lang("su", "Sundanese"),
        Lang("sw", "Swahili"),
        Lang("sv", "Swedish"),
        Lang("tl", "Tagalog"),
        Lang("tg", "Tajik"),
        Lang("ta", "Tamil"),
        Lang("tt", "Tatar"),
        Lang("te", "Telugu"),
        Lang("th", "Thai"),
        Lang("bo", "Tibetan"),
        Lang("tr", "Turkish"),
        Lang("tk", "Turkmen"),
        Lang("uk", "Ukrainian"),
        Lang("ur", "Urdu"),
        Lang("uz", "Uzbek"),
        Lang("vi", "Vietnamese"),
        Lang("cy", "Welsh"),
        Lang("yi", "Yiddish"),
        Lang("yo", "Yoruba"),
    )
}
