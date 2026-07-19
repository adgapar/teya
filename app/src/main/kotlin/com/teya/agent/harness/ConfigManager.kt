package com.teya.agent.harness

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ConfigManager(context: Context) {
    private val prefs = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "teya_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e("ConfigManager", "Error initializing EncryptedSharedPreferences, falling back to standard", e)
        context.getSharedPreferences("teya_prefs_fallback", Context.MODE_PRIVATE)
    }

    var mistralApiKey: String?
        get() = prefs.getString("mistral_api_key", null)?.trim()
        set(value) = prefs.edit().putString("mistral_api_key", value?.trim()).apply()

    /** TTS voice slug (see docs/mistral-voices.md / [com.teya.agent.brain.MistralVoices]), chosen in
     *  Admin's Voice section. Read fresh per request by [com.teya.agent.brain.MistralClient]. */
    var ttsVoice: String
        get() = prefs.getString("tts_voice", com.teya.agent.brain.MistralVoices.DEFAULT)
            ?: com.teya.agent.brain.MistralVoices.DEFAULT
        set(value) = prefs.edit().putString("tts_voice", value).apply()

    /** Which [com.teya.agent.ui.face.AgentVisualization] renders the wall face (and, via its
     *  `Ambient()`, Admin/onboarding's background too) — the id of one entry in
     *  [com.teya.agent.ui.face.AgentVisualizations.all]. Chosen in Admin's Voice panel; resolved
     *  with [com.teya.agent.ui.face.AgentVisualizations.byId], which falls back to the default for
     *  anything unrecognized rather than crashing. */
    var faceStyle: String
        get() = prefs.getString("face_style", "particles") ?: "particles"
        set(value) = prefs.edit().putString("face_style", value).apply()

    /**
     * Household languages, as a CSV of canonical English names ("English,Spanish,Russian"), stored
     * in the order the user picked. Household-level (not per-person). Empty when none set yet.
     * Consumed by [com.teya.agent.household.HouseholdManager] to build the reply-language directive.
     */
    var languages: List<String>
        get() = prefs.getString("household_languages", null)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit()
            .putString("household_languages", value.joinToString(",") { it.trim() })
            .apply()

    /** Whether the user confirmed the detected GPS location is "home" during onboarding/Admin. */
    var homeConfirmed: Boolean
        get() = prefs.getBoolean("home_confirmed", false)
        set(value) = prefs.edit().putBoolean("home_confirmed", value).apply()

    /**
     * Currency code (ISO 4217, e.g. "EUR") for [com.teya.agent.expenses.ExpenseManager]. Not part
     * of onboarding — defaults to EUR since the dev household is in Europe; no Admin UI yet, but
     * settable here once one exists.
     */
    var expenseCurrency: String
        get() = prefs.getString("expense_currency", "EUR") ?: "EUR"
        set(value) = prefs.edit().putString("expense_currency", value.trim().uppercase()).apply()

    /** When the memory "dreamer" (decay/consolidation) last ran; 0 = never. Shown in Admin. */
    var lastDreamAt: Long
        get() = prefs.getLong("last_dream_at", 0L)
        set(value) = prefs.edit().putLong("last_dream_at", value).apply()

    /** One-line summary of the last dream run (what it cooled/pruned), for the Admin monitor. */
    var lastDreamNote: String
        get() = prefs.getString("last_dream_note", "") ?: ""
        set(value) = prefs.edit().putString("last_dream_note", value).apply()

    /** Rolling audit log of recent dream runs, newest-first, each line "millis|note". Capped at 30. */
    var dreamLog: List<String>
        get() = prefs.getString("dream_log", null)?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(value) = prefs.edit().putString("dream_log", value.take(30).joinToString("\n")).apply()

    /** Record a dream run in the rolling log (prepended, so newest is first). */
    fun appendDreamLog(at: Long, note: String) {
        dreamLog = listOf("$at|" + note.replace("\n", " ").replace("|", "/")) + dreamLog
    }

    fun isConfigured(): Boolean {
        return !mistralApiKey.isNullOrBlank()
    }

    // --- Voice tuning (barge-in VAD + wake word) ---
    // Exposed via Admin's "Voice tuning" section so retuning (e.g. for a different device's
    // chipset/mic) doesn't require a rebuild+install cycle. Defaults match the values these knobs
    // were hardcoded to before this section existed.

    /** Silero VAD speech-probability threshold (0-1) for barge-in to treat a frame as speech. */
    var vadThreshold: Float
        get() = prefs.getFloat("vad_threshold", 0.7f)
        set(value) = prefs.edit().putFloat("vad_threshold", value).apply()

    /** Consecutive speech-ms required before barge-in's VAD fires (debounces one-frame spikes). */
    var vadSpeechDurationMs: Int
        get() = prefs.getInt("vad_speech_duration_ms", 50)
        set(value) = prefs.edit().putInt("vad_speech_duration_ms", value).apply()

    /** Trailing silence-ms before barge-in's VAD resets to non-speech. */
    var vadSilenceDurationMs: Int
        get() = prefs.getInt("vad_silence_duration_ms", 300)
        set(value) = prefs.edit().putInt("vad_silence_duration_ms", value).apply()

    /** Software mic gain applied to barge-in audio before VAD scoring (device has no hardware AGC). */
    var bargeInGain: Float
        get() = prefs.getFloat("barge_in_gain", 6.0f)
        set(value) = prefs.edit().putFloat("barge_in_gain", value).apply()

    /** Pause (ms) after each spoken sentence on the gap-gated barge-in fallback, to catch an interrupt. */
    var bargeInGapMs: Long
        get() = prefs.getLong("barge_in_gap_ms", 350L)
        set(value) = prefs.edit().putLong("barge_in_gap_ms", value).apply()

    /** hey_teya (microWakeWord) classifier probability cutoff (0-1) — from hey_teya.json's calibration. */
    var wakeWordThreshold: Float
        get() = prefs.getFloat("wake_word_threshold", 0.53f)
        set(value) = prefs.edit().putFloat("wake_word_threshold", value).apply()

    /** Software mic gain applied before the wake-word model (device has no hardware AGC). */
    var wakeWordInputGain: Float
        get() = prefs.getFloat("wake_word_input_gain", 6.0f)
        set(value) = prefs.edit().putFloat("wake_word_input_gain", value).apply()

    /** Consecutive over-threshold classifier frames required before the wake word fires (hey_teya.json's sliding_window_size). */
    var wakeWordPatience: Int
        get() = prefs.getInt("wake_word_patience", 3)
        set(value) = prefs.edit().putInt("wake_word_patience", value).apply()

    /**
     * Extra loudness (dB) applied to Teya's own TTS output via [android.media.audiofx.LoudnessEnhancer]
     * — a real gain boost beyond 100% device volume, with the effect's built-in limiting to avoid
     * harsh clipping. Deliberately separate from the device's own volume control (physical
     * buttons/slider): that stays manual so the household can silence her (kids asleep, etc.)
     * without this boost fighting it — LoudnessEnhancer amplifies whatever the device volume
     * already is, it doesn't override it.
     */
    var ttsVolumeBoostDb: Float
        get() = prefs.getFloat("tts_volume_boost_db", 6.0f)
        set(value) = prefs.edit().putFloat("tts_volume_boost_db", value).apply()

    /**
     * Cosine-similarity threshold (0-1) for per-speaker voice ID matching a captured wake-word
     * audio window against an enrolled voiceprint — see `household/SpeakerIdManager.kt`. Starting
     * point from the Phase 0 spike (`docs/experiments.md`): real-speech same-speaker scored ~0.71
     * at 3.5s clips, worst different-speaker (closest-pitch pair) ~0.52 — 0.6 sits between them.
     * Needs live tuning against real household voices and this device's mic, same as every other
     * knob here.
     */
    var speakerIdThreshold: Float
        get() = prefs.getFloat("speaker_id_threshold", 0.6f)
        set(value) = prefs.edit().putFloat("speaker_id_threshold", value).apply()

    /**
     * Higher cosine-similarity bar (0-1) above which Teya is allowed to actually *use* a voice
     * match — e.g. greet someone by name — rather than only silently disambiguating a shared
     * alias (see `household/HouseholdManager.speakerContextBlock`). Default is a conservative
     * placeholder, not yet calibrated from real data: the first live tests (docs/experiments.md)
     * were too few and too close to the base threshold to trust a specific number here yet.
     */
    var speakerIdConfidentThreshold: Float
        get() = prefs.getFloat("speaker_id_confident_threshold", 0.8f)
        set(value) = prefs.edit().putFloat("speaker_id_confident_threshold", value).apply()

    /**
     * Quiet hours: while enabled and the current local time falls in [quietHoursStartMin,
     * quietHoursEndMin) (wrapping past midnight, e.g. default 00:00-07:00), Teya still responds to
     * wake word/tap and runs the conversation as normal (STT, tool calls, on-screen text) but stays
     * silent — no chime, no TTS — see [com.teya.agent.voice.VoicePipeline]'s textToSpeech/playTone.
     */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean("quiet_hours_enabled", false)
        set(value) = prefs.edit().putBoolean("quiet_hours_enabled", value).apply()

    /** Quiet hours start, minutes since local midnight (default 0 = 00:00). */
    var quietHoursStartMin: Int
        get() = prefs.getInt("quiet_hours_start_min", 0)
        set(value) = prefs.edit().putInt("quiet_hours_start_min", value).apply()

    /** Quiet hours end, minutes since local midnight (default 7*60 = 07:00). */
    var quietHoursEndMin: Int
        get() = prefs.getInt("quiet_hours_end_min", 7 * 60)
        set(value) = prefs.edit().putInt("quiet_hours_end_min", value).apply()

    /** When any Voice tuning knob was last actually changed in Admin; 0 = never. Drives the idle
     *  face's ambient status mote so a retune is visible without opening Admin. */
    var lastTuningChangedAt: Long
        get() = prefs.getLong("last_tuning_changed_at", 0L)
        set(value) = prefs.edit().putLong("last_tuning_changed_at", value).apply()

    /** When Mistral last rejected a request as unauthorized (bad/expired API key); 0 = never. Both
     *  STT and TTS fail silently in this case (TTS itself is broken, so she can't voice the error),
     *  so this is surfaced visually instead — on the idle face and in Admin's API section. */
    var lastAuthErrorAt: Long
        get() = prefs.getLong("last_auth_error_at", 0L)
        set(value) = prefs.edit().putLong("last_auth_error_at", value).apply()

    /** The actual caption shown for the last auth error. Persisted (not just broadcast) because the
     *  live "com.teya.agent.STATE_UPDATE"/TRANSCRIPT_UPDATE broadcasts aren't queued — if
     *  MainActivity's receiver registers even slightly after HarnessService sends them (e.g. right
     *  after SetupActivity hands off), the specific message is silently dropped and only the state
     *  change survives. Read as the BRAIN_OFF caption's fallback so a missed broadcast still shows
     *  the real reason, not a generic placeholder. */
    var lastAuthErrorNote: String
        get() = prefs.getString("last_auth_error_note", "") ?: ""
        set(value) = prefs.edit().putString("last_auth_error_note", value).apply()
}
