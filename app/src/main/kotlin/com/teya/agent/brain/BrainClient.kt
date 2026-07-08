package com.teya.agent.brain

// Simple interface for the LLM brain
interface BrainClient {
    /**
     * @param liveContext optional "live device state" (current time, location, …) the harness
     * refreshes each turn and the provider folds into the system prompt, so the model already
     * knows these facts and needn't spend a tool round-trip fetching them.
     */
    suspend fun processText(history: List<ChatMessage>, liveContext: String? = null): BrainResponse

    /**
     * Streaming variant of [processText]: [onText] is invoked with the reply text accumulated *so
     * far* as tokens arrive, so the UI can render it live and the harness can speak it
     * sentence-by-sentence in parallel. Returns the same completed [BrainResponse] (final text +
     * any tool calls). Default implementation is non-streaming — one [processText] call, emitted
     * once — so providers without streaming still work.
     */
    suspend fun streamChat(
        history: List<ChatMessage>,
        liveContext: String? = null,
        onText: suspend (String) -> Unit,
    ): BrainResponse {
        val response = processText(history, liveContext)
        if (response.speechResponse.isNotBlank()) onText(response.speechResponse)
        return response
    }

    /**
     * Embed [text] into a vector for memory RAG (cosine over the general pool). Returns null if the
     * provider has no embedding model or the call fails — callers fall back to keyword search.
     */
    suspend fun embed(text: String): FloatArray? = null
}

/**
 * One turn in the conversation. [role] is "system", "user", "assistant", or "tool".
 * Most turns are just role + content. The extra fields carry a tool round-trip (M7):
 * an "assistant" turn where the model decided to act sets [toolCalls]; the matching
 * "tool" turn feeding the result back sets [toolCallId] + [name].
 */
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val name: String? = null,
)

data class BrainResponse(
    val speechResponse: String,
    /** All tools the model asked for in this response — run them all (batch), then feed results back. */
    val toolCalls: List<ToolCall> = emptyList()
)

data class ToolCall(
    /** Provider-assigned id; needed to correlate the result back to this call. */
    val id: String,
    val functionName: String,
    val arguments: Map<String, String>
)
