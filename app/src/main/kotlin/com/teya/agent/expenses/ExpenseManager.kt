package com.teya.agent.expenses

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * One logged expense. Amounts are stored as integer cents ([amountCents]) — never as a floating
 * point euro amount — so totals (see [ExpenseManager.query]) sum exactly instead of drifting.
 */
@Serializable
data class Expense(
    val id: Long,
    val timestampMillis: Long,
    val amountCents: Long,
    val currency: String,
    val category: String,
    val item: String,
)

/** Aggregated result of [ExpenseManager.query] — computed in code, never by the model. */
data class ExpenseSummary(
    val totalCents: Long,
    val currency: String,
    val count: Int,
    val byCategory: Map<String, Long>,
)

/**
 * The family expense log — Teya-owned, like [com.teya.agent.shopping.ShoppingListManager].
 * Backed by SharedPreferences (JSON), persists across reboots. The model only extracts and
 * classifies each expense as it's logged; all totals/breakdowns are computed here in code
 * (the deterministic-math principle — the LLM never sums).
 */
class ExpenseManager(context: Context) {

    private val prefs = context.getSharedPreferences("teya_expenses", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Small fixed taxonomy — deliberately not user-customizable, to keep voice categorization simple. */
    object Categories {
        const val GROCERIES = "groceries"
        const val DINING = "dining"
        const val TRANSPORT = "transport"
        const val UTILITIES = "utilities"
        const val HEALTH = "health"
        const val HOUSEHOLD = "household"
        const val ENTERTAINMENT = "entertainment"
        const val KIDS = "kids"
        const val OTHER = "other"

        val all = listOf(GROCERIES, DINING, TRANSPORT, UTILITIES, HEALTH, HOUSEHOLD, ENTERTAINMENT, KIDS, OTHER)

        /** Maps a free-form model-provided category to the nearest fixed one, defaulting to [OTHER]. */
        fun normalize(raw: String?): String {
            val c = raw?.trim()?.lowercase().orEmpty()
            return all.firstOrNull { it == c } ?: OTHER
        }
    }

    /** Log one expense. Returns the stored entry (with its assigned id and normalized category). */
    @Synchronized
    fun log(amount: Double, currency: String, category: String?, item: String): Expense {
        val current = load().toMutableList()
        val entry = Expense(
            id = (current.maxOfOrNull { it.id } ?: 0L) + 1,
            timestampMillis = System.currentTimeMillis(),
            amountCents = (amount * 100).roundToLong(),
            currency = currency,
            category = Categories.normalize(category),
            item = item.trim(),
        )
        current.add(entry)
        save(current)
        return entry
    }

    /** Remove the most recent expense whose item matches [query] (case-insensitive contains). */
    @Synchronized
    fun delete(query: String?): Expense? {
        val current = load().toMutableList()
        if (current.isEmpty()) return null
        val match = if (query.isNullOrBlank()) {
            current.maxByOrNull { it.timestampMillis }
        } else {
            current.filter { it.item.contains(query.trim(), ignoreCase = true) }
                .maxByOrNull { it.timestampMillis }
        }
        if (match != null) {
            current.remove(match)
            save(current)
        }
        return match
    }

    /**
     * Aggregate expenses in [startMillis, endMillis) optionally filtered by [category], entirely
     * in code — total, count, and a per-category breakdown, so the model only ever phrases numbers
     * it's handed, never adds them up itself.
     */
    @Synchronized
    fun query(startMillis: Long, endMillis: Long, category: String? = null): ExpenseSummary {
        val normalizedCategory = category?.let { Categories.normalize(it) }
        val matches = load().filter {
            it.timestampMillis in startMillis until endMillis &&
                (normalizedCategory == null || it.category == normalizedCategory)
        }
        val currency = matches.firstOrNull()?.currency ?: "EUR"
        val byCategory = matches.groupingBy { it.category }.fold(0L) { acc, e -> acc + e.amountCents }
        return ExpenseSummary(
            totalCents = matches.sumOf { it.amountCents },
            currency = currency,
            count = matches.size,
            byCategory = byCategory,
        )
    }

    private fun load(): List<Expense> = try {
        prefs.getString(KEY, null)?.let { json.decodeFromString<List<Expense>>(it) } ?: emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read expense log", e)
        emptyList()
    }

    private fun save(list: List<Expense>) {
        prefs.edit().putString(KEY, json.encodeToString(list)).apply()
    }

    companion object {
        private const val TAG = "ExpenseManager"
        private const val KEY = "entries"

        /** Formats cents as a plain decimal amount, e.g. 1250L -> "12.50". */
        fun formatCents(cents: Long): String = "%.2f".format(cents / 100.0)
    }
}
