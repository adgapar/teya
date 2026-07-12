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
     * listening — varied per language so it doesn't feel canned, and so a household that speaks
     * e.g. Spanish hears "¿Sí?" rather than an English "Yes?" translated on the fly by the LLM
     * (which would cost a round-trip this instant reply can't afford). Covers all of TTS_VOICED;
     * falls back to English's list for anything else.
     */
    private val GREETINGS: Map<String, List<String>> = mapOf(
        "English" to listOf("Yes?", "Mm-hmm?", "I'm listening.", "Go ahead.", "What's up?"),
        "Spanish" to listOf("¿Sí?", "Dime.", "Te escucho.", "¿Qué pasa?"),
        "French" to listOf("Oui ?", "Je t'écoute.", "Vas-y.", "Dis-moi."),
        "German" to listOf("Ja?", "Ich höre.", "Sag's mir.", "Was gibt's?"),
        "Portuguese" to listOf("Sim?", "Diz.", "Estou a ouvir.", "Pode falar."),
        "Italian" to listOf("Sì?", "Dimmi.", "Ti ascolto.", "Vai pure."),
        "Dutch" to listOf("Ja?", "Ik luister.", "Zeg het maar.", "Ga je gang."),
        "Hindi" to listOf("हाँ?", "बोलिए।", "मैं सुन रही हूँ।", "कहिए।"),
        "Arabic" to listOf("نعم؟", "تفضل.", "أنا أستمع.", "قل لي."),
    )

    fun greetings(language: String): List<String> = GREETINGS[language] ?: GREETINGS.getValue("English")
}
