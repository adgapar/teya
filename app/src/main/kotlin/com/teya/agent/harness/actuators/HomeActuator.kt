package com.teya.agent.harness.actuators

import android.util.Log
import com.teya.agent.brain.ToolCall
import com.teya.agent.expenses.ExpenseManager
import com.teya.agent.harness.ConfigManager
import com.teya.agent.shopping.ShoppingListManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The two Teya-owned household stores: the shopping list and the expense log. */
class HomeActuator(
    private val shoppingList: ShoppingListManager,
    private val expenses: ExpenseManager,
    private val config: ConfigManager,
) {
    suspend fun run(tool: ToolCall): String = when (tool.functionName) {
        "add_to_shopping_list" -> {
            val items = splitItems(tool.arguments["items"])
            if (items.isEmpty()) {
                "What should I add to the shopping list?"
            } else {
                val added = shoppingList.add(items)
                when {
                    added.isEmpty() -> "That's already on the list."
                    added.size == 1 -> "Added ${added[0]} to the shopping list."
                    else -> "Added ${added.joinToString(", ")} to the shopping list."
                }
            }
        }
        "remove_from_shopping_list" -> {
            val removed = shoppingList.remove(splitItems(tool.arguments["items"]))
            if (removed.isEmpty()) "I didn't find that on the list."
            else "Removed ${removed.joinToString(", ")} from the shopping list."
        }
        "read_shopping_list" -> {
            val items = shoppingList.items()
            if (items.isEmpty()) "The shopping list is empty."
            // Hand the model the raw list; it groups by category (dairy, produce, …) when speaking.
            else "Shopping list (${items.size} items): ${items.joinToString(", ")}."
        }
        "clear_shopping_list" -> {
            val n = shoppingList.clear()
            if (n == 0) "The shopping list was already empty." else "Cleared the shopping list ($n items)."
        }
        "log_expense" -> {
            val amount = tool.arguments["amount"]?.toDoubleOrNull()
            val item = tool.arguments["item"]?.trim()
            if (amount == null || amount <= 0 || item.isNullOrBlank()) {
                "I need an amount and what it was for to log that expense."
            } else {
                val currency = tool.arguments["currency"]?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
                    ?: config.expenseCurrency
                val timestamp = tool.arguments["date"]?.let { parseIsoToMillis(it) } ?: System.currentTimeMillis()
                val entry = expenses.log(amount, currency, tool.arguments["category"], item, timestamp)
                val whenStr = if (tool.arguments["date"] != null) {
                    " on " + Instant.ofEpochMilli(entry.timestampMillis).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
                } else ""
                "Logged ${ExpenseManager.formatCents(entry.amountCents)} $currency for $item (${entry.category})$whenStr."
            }
        }
        "query_expenses" -> {
            val now = ZonedDateTime.now()
            val period = tool.arguments["period"]?.trim()?.lowercase() ?: "month"
            val startDate = when (period) {
                "today" -> now.toLocalDate()
                "week" -> now.toLocalDate().minusDays(6)
                "year" -> now.toLocalDate().withDayOfYear(1)
                "all" -> null
                else -> now.toLocalDate().withDayOfMonth(1)
            }
            val startMillis = startDate?.atStartOfDay(now.zone)?.toInstant()?.toEpochMilli() ?: 0L
            val endMillis = now.toInstant().toEpochMilli() + 1
            val category = tool.arguments["category"]
            val summary = expenses.query(startMillis, endMillis, category)
            if (summary.count == 0) {
                "No expenses logged for that period" + (category?.let { " in $it" } ?: "") + "."
            } else {
                val breakdown = summary.byCategory.entries.joinToString(", ") {
                    "${it.key}: ${ExpenseManager.formatCents(it.value)} ${summary.currency}"
                }
                "Total ${ExpenseManager.formatCents(summary.totalCents)} ${summary.currency} over " +
                    "${summary.count} expense(s). By category: $breakdown."
            }
        }
        "delete_expense" -> {
            val removed = expenses.delete(tool.arguments["item"])
            if (removed == null) "I couldn't find an expense to remove."
            else "Removed ${ExpenseManager.formatCents(removed.amountCents)} ${removed.currency} for ${removed.item}."
        }
        else -> "Unknown household tool: ${tool.functionName}"
    }

    private fun splitItems(raw: String?): List<String> =
        raw?.split(Regex("\\s*(?:,|;|\\band\\b|\\n)\\s*"))?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    private fun parseIsoToMillis(iso: String): Long? {
        val zone = ZoneId.systemDefault()
        return runCatching { LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() }
            .recoverCatching { LocalDate.parse(iso).atStartOfDay(zone).toInstant().toEpochMilli() }
            .getOrNull()
    }

    private companion object { const val TAG = "HomeActuator" }
}
