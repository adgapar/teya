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

    fun isConfigured(): Boolean {
        return !mistralApiKey.isNullOrBlank()
    }
}
