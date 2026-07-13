package com.teya.agent.brain

/**
 * Mistral's built-in Voxtral TTS voice catalog — see docs/mistral-voices.md. Hardcoded rather than
 * fetched live from `GET /v1/audio/voices`: this is a fixed catalog (4 actors × emotion variants),
 * and Admin already loads everything else eagerly with no network round-trip.
 */
data class MistralVoice(val slug: String, val actor: String, val locale: String, val emotion: String, val tags: String)

object MistralVoices {
    /** Matches [com.teya.agent.brain.MistralClient]'s previous hardcoded default. */
    const val DEFAULT = "fr_marie_happy"

    val ALL: List<MistralVoice> = listOf(
        MistralVoice("gb_jane_neutral", "Jane", "en-GB", "Neutral", "clear, measured, neutral"),
        MistralVoice("gb_jane_confident", "Jane", "en-GB", "Confident", "assured, poised, confident"),
        MistralVoice("gb_jane_curious", "Jane", "en-GB", "Curious", "inquisitive, open, curious"),
        MistralVoice("gb_jane_sad", "Jane", "en-GB", "Sad", "soft, subdued, sad"),
        MistralVoice("gb_jane_frustrated", "Jane", "en-GB", "Frustrated", "tense, clipped, frustrated"),
        MistralVoice("gb_jane_confused", "Jane", "en-GB", "Confused", "hesitant, uncertain, confused"),
        MistralVoice("gb_jane_jealousy", "Jane", "en-GB", "Jealousy", "bitter, strained, jealous"),
        MistralVoice("gb_jane_shameful", "Jane", "en-GB", "Shameful", "quiet, remorseful, ashamed"),
        MistralVoice("gb_jane_sarcasm", "Jane", "en-GB", "Sarcasm", "dry, wry, sarcastic"),

        MistralVoice("fr_marie_neutral", "Marie", "fr-FR", "Neutral", "composed, steady, neutral"),
        MistralVoice("fr_marie_happy", "Marie", "fr-FR", "Happy", "warm, radiant, happy"),
        MistralVoice("fr_marie_excited", "Marie", "fr-FR", "Excited", "vibrant, bubbly, excited"),
        MistralVoice("fr_marie_curious", "Marie", "fr-FR", "Curious", "bright, probing, curious"),
        MistralVoice("fr_marie_sad", "Marie", "fr-FR", "Sad", "muted, heavy, sad"),
        MistralVoice("fr_marie_angry", "Marie", "fr-FR", "Angry", "fierce, sharp, angry"),

        MistralVoice("en_paul_neutral", "Paul", "en-US", "Neutral", "relaxed, balanced, neutral"),
        MistralVoice("en_paul_happy", "Paul", "en-US", "Happy", "sunny, easygoing, happy"),
        MistralVoice("en_paul_cheerful", "Paul", "en-US", "Cheerful", "upbeat, breezy, cheerful"),
        MistralVoice("en_paul_confident", "Paul", "en-US", "Confident", "bold, punchy, confident"),
        MistralVoice("en_paul_excited", "Paul", "en-US", "Excited", "bouncy, spirited, excited"),
        MistralVoice("en_paul_frustrated", "Paul", "en-US", "Frustrated", "edgy, snappy, frustrated"),
        MistralVoice("en_paul_sad", "Paul", "en-US", "Sad", "heavy, hushed, sad"),
        MistralVoice("en_paul_angry", "Paul", "en-US", "Angry", "raw, gruff, angry"),

        MistralVoice("gb_oliver_neutral", "Oliver", "en-GB", "Neutral", "calm, even, neutral"),
        MistralVoice("gb_oliver_cheerful", "Oliver", "en-GB", "Cheerful", "bright, lively, cheerful"),
        MistralVoice("gb_oliver_confident", "Oliver", "en-GB", "Confident", "firm, decisive, confident"),
        MistralVoice("gb_oliver_curious", "Oliver", "en-GB", "Curious", "thoughtful, engaged, curious"),
        MistralVoice("gb_oliver_excited", "Oliver", "en-GB", "Excited", "energetic, crisp, excited"),
        MistralVoice("gb_oliver_sad", "Oliver", "en-GB", "Sad", "low, hollow, sad"),
        MistralVoice("gb_oliver_angry", "Oliver", "en-GB", "Angry", "intense, forceful, angry"),
    )

    fun bySlug(slug: String): MistralVoice = ALL.find { it.slug == slug } ?: ALL.first { it.slug == DEFAULT }
}
