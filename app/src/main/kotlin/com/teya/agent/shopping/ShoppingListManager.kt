package com.teya.agent.shopping

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The family shopping list — Teya-owned because Android has no native shopping-list provider.
 * Backed by SharedPreferences (JSON), so it **persists across reboots** (unlike timers): items
 * accrue over days until someone goes shopping. Case-insensitive dedup on add.
 */
class ShoppingListManager(context: Context) {

    private val prefs = context.getSharedPreferences("teya_shopping", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun items(): List<String> = load()

    /** Add items (skipping ones already present, case-insensitive). Returns those actually added. */
    @Synchronized
    fun add(newItems: List<String>): List<String> {
        val current = load().toMutableList()
        val added = mutableListOf<String>()
        for (raw in newItems) {
            val item = raw.trim()
            if (item.isBlank()) continue
            if (current.none { it.equals(item, ignoreCase = true) }) {
                current.add(item)
                added.add(item)
            }
        }
        if (added.isNotEmpty()) save(current)
        return added
    }

    /** Remove items by exact or contains match (case-insensitive). Returns those removed. */
    @Synchronized
    fun remove(queries: List<String>): List<String> {
        val current = load().toMutableList()
        val removed = mutableListOf<String>()
        for (raw in queries) {
            val q = raw.trim()
            if (q.isBlank()) continue
            val match = current.firstOrNull { it.equals(q, ignoreCase = true) }
                ?: current.firstOrNull { it.contains(q, ignoreCase = true) }
            if (match != null) {
                current.remove(match)
                removed.add(match)
            }
        }
        if (removed.isNotEmpty()) save(current)
        return removed
    }

    /** Empty the list. Returns how many items were cleared. */
    @Synchronized
    fun clear(): Int {
        val n = load().size
        if (n > 0) save(emptyList())
        return n
    }

    private fun load(): List<String> = try {
        prefs.getString(KEY, null)?.let { json.decodeFromString<List<String>>(it) } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read shopping list", e)
        emptyList()
    }

    private fun save(list: List<String>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    companion object {
        private const val TAG = "ShoppingListManager"
        private const val KEY = "items"
    }
}
