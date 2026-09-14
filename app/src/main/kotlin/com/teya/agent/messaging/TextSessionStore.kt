package com.teya.agent.messaging

import android.content.Context
import com.teya.agent.brain.ChatMessage
import com.teya.agent.household.TextSession
import com.teya.agent.safety.TeyaDatabase
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Conversation state for the text transport, one session per household member.
 *
 * The voice loop can keep its history in a local ([HarnessService.runConversation]) because a spoken
 * conversation starts and ends inside one function call. A texted one can't: turns are minutes or
 * hours apart, and the `START_STICKY` service is routinely killed in between — so history is
 * persisted (Room `text_session`) and reloaded per message.
 *
 * "Where does one conversation end and the next begin" has no silence to key off either, so it's an
 * **idle timeout**: a message arriving more than [IDLE_TIMEOUT_MS] after the last one starts a fresh
 * history rather than continuing a stale one ("add milk" three hours later isn't a follow-up to
 * this morning's calendar question). [load] reports that as [Session.staleHistory] so the caller can
 * hand the closed conversation to episodic memory before dropping it, the same way a finished voice
 * conversation is captured.
 */
class TextSessionStore(context: Context) {
    private val dao = TeyaDatabase.get(context).textSessionDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Only the user/assistant turns are persisted — a tool round-trip means nothing outside its own turn. */
    @Serializable
    private data class StoredTurn(val role: String, val content: String)

    /**
     * [history] is the live conversation to continue (empty when this message opens a new one);
     * [staleHistory] is the previous conversation that just timed out, if there was one.
     */
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
        val kept = history
            .filter { (it.role == "user" || it.role == "assistant") && !it.content.isNullOrBlank() }
            .takeLast(MAX_TURNS)
            .map { StoredTurn(it.role, it.content!!) }
        dao.upsert(TextSession(lookupKey, json.encodeToString(ListSerializer(StoredTurn.serializer()), kept), now))
    }

    suspend fun clear(lookupKey: String) = dao.delete(lookupKey)

    private fun decode(raw: String): List<ChatMessage> = runCatching {
        json.decodeFromString(ListSerializer(StoredTurn.serializer()), raw)
            .map { ChatMessage(role = it.role, content = it.content) }
    }.getOrDefault(emptyList())

    companion object {
        /** A guess, per the design doc — needs real use to tune. */
        const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L
        /** Same bound as the voice loop's MAX_HISTORY, applied at rest as well as in flight. */
        private const val MAX_TURNS = 10
    }
}
