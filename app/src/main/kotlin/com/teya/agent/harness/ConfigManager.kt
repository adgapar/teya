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

    /** Wake-word detection probability threshold (0-1) — lower fires more easily but risks false positives. */
    var wakeWordThreshold: Float
        get() = prefs.getFloat("wake_word_threshold", 0.2f)
        set(value) = prefs.edit().putFloat("wake_word_threshold", value).apply()

    /** Software mic gain applied before the wake-word model (device has no hardware AGC). */
    var wakeWordInputGain: Float
        get() = prefs.getFloat("wake_word_input_gain", 6.0f)
        set(value) = prefs.edit().putFloat("wake_word_input_gain", value).apply()

    /** Consecutive over-threshold frames required before the wake word fires. */
    var wakeWordPatience: Int
        get() = prefs.getInt("wake_word_patience", 1)
        set(value) = prefs.edit().putInt("wake_word_patience", value).apply()
}
