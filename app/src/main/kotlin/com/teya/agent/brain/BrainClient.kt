package com.teya.agent.brain

// Simple interface for the LLM brain
interface BrainClient {
    suspend fun processText(history: List<ChatMessage>): BrainResponse
}

/** One turn in the conversation. [role] is "user" or "assistant". */
data class ChatMessage(
    val role: String,
    val content: String
)

data class BrainResponse(
    val speechResponse: String,
    val toolCall: ToolCall? = null
)

data class ToolCall(
    val functionName: String,
    val arguments: Map<String, String>
)
