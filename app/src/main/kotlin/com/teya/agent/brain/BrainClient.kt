package com.teya.agent.brain

// Simple interface for the LLM brain
interface BrainClient {
    suspend fun processText(input: String): BrainResponse
}

data class BrainResponse(
    val speechResponse: String,
    val toolCall: ToolCall? = null
)

data class ToolCall(
    val functionName: String,
    val arguments: Map<String, String>
)
