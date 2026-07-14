package com.teya.agent.household

/**
 * The 13 languages Voxtral STT can transcribe, in canonical display order (matches the onboarding
 * prototype). A subset of 9 have TTS voices (Voxtral TTS, via zero-shot voice cloning /
 * cross-lingual / code-mixing); the remaining 4 (Russian, Chinese, Japanese, Korean) are
 * understand-only.
 *
 * This split is the crux of the language/TTS trap: the LLM will happily generate Russian text, but
 * TTS can't voice it. [HouseholdManager.profileContextBlock] derives the speakable set as
 * (household ∩ [TTS_VOICED]) and constrains the model's reply language to it.
 */
object Languages {
    val ALL: List<String> = listOf(
        "English", "Spanish", "French", "German", "Portuguese", "Italian", "Dutch",
        "Hindi", "Arabic", "Russian", "Chinese", "Japanese", "Korean",
    )

    /** The 9 Voxtral TTS can speak. RU/ZH/JA/KO are deliberately excluded — understand-only. */
    val TTS_VOICED: Set<String> = setOf(
        "English", "Spanish", "French", "German", "Portuguese", "Italian", "Dutch",
        "Hindi", "Arabic",
    )

    fun isVoiced(language: String): Boolean = language in TTS_VOICED

    /**
     * Short acknowledgement Teya speaks on trigger (wake word or tap), before she starts
     * listening — consistently "Yes?" (or that language's equivalent), so a household that speaks
     * e.g. Spanish hears "¿Sí?" rather than an English "Yes?" translated on the fly by the LLM
     * (which would cost a round-trip this instant reply can't afford). Covers all of TTS_VOICED;
     * falls back to English for anything else.
     */
    private val GREETING: Map<String, String> = mapOf(
        "English" to "Yes?",
        "Spanish" to "¿Sí?",
        "French" to "Oui ?",
        "German" to "Ja?",
        "Portuguese" to "Sim?",
        "Italian" to "Sì?",
        "Dutch" to "Ja?",
        "Hindi" to "हाँ?",
        "Arabic" to "نعم؟",
    )

    fun greeting(language: String): String = GREETING[language] ?: GREETING.getValue("English")
}
