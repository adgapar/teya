package com.teya.agent.messaging

import com.teya.agent.brain.ChatMessage
import com.teya.agent.brain.ToolCall
import android.content.Context
import com.teya.agent.household.TextSession
import com.teya.agent.safety.TeyaDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persisted conversation state for the text transport, one session per household member.
 * A message arriving more than [IDLE_TIMEOUT_MS] after the last one opens a fresh history.
 */
class TextSessionStore(context: Context) {
    private val dao = TeyaDatabase.get(context).textSessionDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class StoredCall(val id: String, val name: String, val arguments: Map<String, String>)

    @Serializable
    private data class StoredTurn(
        val role: String,
        val content: String? = null,
        val toolCalls: List<StoredCall>? = null,
        val toolCallId: String? = null,
        val name: String? = null,
    )

    /** [staleHistory] is a timed-out conversation the caller should capture before dropping. */
    data class Session(
        val history: MutableList<ChatMessage>,
        val staleHistory: List<ChatMessage>,
    )

    suspend fun load(lookupKey: String, now: Long = System.currentTimeMillis()): Session {
        val row = dao.find(lookupKey) ?: return Session(mutableListOf(), emptyList())
        val turns = decode(row.transcript)
        return if (now - row.lastActivityAt > IDLE_TIMEOUT_MS) {
            Session(mutableListOf(), turns)
        } else {
            Session(turns.toMutableList(), emptyList())
        }
    }

    suspend fun save(lookupKey: String, history: List<ChatMessage>, now: Long = System.currentTimeMillis()) {
        val kept = trimmed(history).map { m ->
            StoredTurn(
                role = m.role,
                content = m.content,
                toolCalls = m.toolCalls?.map { StoredCall(it.id, it.functionName, it.arguments) },
                toolCallId = m.toolCallId,
                name = m.name,
            )
        }
        dao.upsert(TextSession(lookupKey, json.encodeToString(ListSerializer(StoredTurn.serializer()), kept), now))
    }

    suspend fun clear(lookupKey: String) = dao.delete(lookupKey)

    private fun decode(raw: String): List<ChatMessage> = runCatching {
        json.decodeFromString(ListSerializer(StoredTurn.serializer()), raw).map { t ->
            ChatMessage(
                role = t.role,
                content = t.content,
                toolCalls = t.toolCalls?.map { ToolCall(it.id, it.name, it.arguments) },
                toolCallId = t.toolCallId,
                name = t.name,
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L

        private const val MAX_MESSAGES = 40

        /** Last [MAX_MESSAGES], with the cut repaired: a `tool` result whose call was trimmed makes the provider reject the request. */
        internal fun trimmed(history: List<ChatMessage>): List<ChatMessage> {
            if (history.size <= MAX_MESSAGES) return dropLeadingOrphans(history)
            return dropLeadingOrphans(history.takeLast(MAX_MESSAGES))
        }

        private fun dropLeadingOrphans(window: List<ChatMessage>): List<ChatMessage> {
            var from = 0
            while (from < window.size && window[from].role == "tool") from++
            return if (from == 0) window else window.subList(from, window.size)
        }
    }
}
