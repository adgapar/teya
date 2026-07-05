package com.teya.agent.harness

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class ConfigManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "teya_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var mistralApiKey: String?
        get() = prefs.getString("mistral_api_key", null)
        set(value) = prefs.edit().putString("mistral_api_key", value).apply()

    fun isConfigured(): Boolean {
        return !mistralApiKey.isNullOrBlank()
    }
}
