package com.teya.agent.harness.actuators

import android.util.Log
import com.teya.agent.brain.BrainClient
import com.teya.agent.brain.ToolCall
import com.teya.agent.household.HouseholdManager
import com.teya.agent.household.MemoryManager

/** Teya's long-term memory of the family: remember, forget, search. */
class MemoryActuator(
    private val memoryManager: MemoryManager,
    private val householdManager: HouseholdManager,
    private val brainClient: BrainClient,
) {
    suspend fun run(tool: ToolCall): String = when (tool.functionName) {
        "remember" -> {
            val fact = tool.arguments["fact"]?.trim().orEmpty()
            if (fact.isEmpty()) {
                "I need to know what to remember."
            } else {
                // Link to a member when `about` names one; otherwise store it as a family-wide fact.
                val about = tool.arguments["about"]?.trim().orEmpty()
                val member = about.takeIf { it.isNotEmpty() }
                    ?.let { householdManager.resolveMember(it, householdManager.members()) }
                // Embed every memory so it stays semantically searchable once it cools out of the
                // always-loaded block (persona) or lives in the search-only general pool.
                val embedding = brainClient.embed(fact)
                val id = if (member?.lookupKey != null) {
                    memoryManager.remember(fact, MemoryManager.SUBJECT_CONTACT, member.lookupKey, tool.arguments["category"], embedding)
                } else {
                    memoryManager.remember(fact, MemoryManager.SUBJECT_GENERAL, null, tool.arguments["category"], embedding)
                }
                if (id < 0) "I couldn't save that."
                else "Saved to memory" + (member?.displayName?.let { " (about $it)" } ?: "") + "."
            }
        }
        "forget" -> {
            val fact = tool.arguments["fact"]?.trim().orEmpty()
            if (fact.isEmpty()) {
                "Tell me what to forget."
            } else {
                val about = tool.arguments["about"]?.trim().orEmpty()
                val member = about.takeIf { it.isNotEmpty() }
                    ?.let { householdManager.resolveMember(it, householdManager.members()) }
                val n = memoryManager.forget(fact, member?.lookupKey)
                if (n == 0) "I didn't have anything like that saved." else "Forgotten."
            }
        }
        "search_memory" -> {
            val query = tool.arguments["query"]?.trim().orEmpty()
            if (query.isEmpty()) {
                "What should I look for in my memory?"
            } else {
                val hits = memoryManager.search(query, brainClient.embed(query))
                if (hits.isEmpty()) "I don't have anything about that saved."
                else hits.joinToString("; ") { it.text }
            }
        }
        else -> "Unknown memory tool: ${tool.functionName}"
    }

    private companion object { const val TAG = "MemoryActuator" }
}
