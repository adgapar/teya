package com.teya.agent.brain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class MistralChatRequest(
    val model: String,
    val messages: List<MistralMessage>,
    val tools: List<MistralTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null
)

@Serializable
data class MistralMessage(
    val role: String,
    val content: String,
    @SerialName("tool_calls") val toolCalls: List<MistralToolCall>? = null
)

@Serializable
data class MistralTool(
    val type: String = "function",
    val function: MistralFunctionDef
)

@Serializable
data class MistralFunctionDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class MistralToolCall(
    val id: String,
    val type: String,
    val function: MistralFunctionCall
)

@Serializable
data class MistralFunctionCall(
    val name: String,
    val arguments: String // Mistral returns arguments as a JSON string
)

@Serializable
data class MistralChatResponse(
    val id: String,
    val choices: List<MistralChoice>
)

@Serializable
data class MistralChoice(
    val index: Int,
    val message: MistralMessage,
    @SerialName("finish_reason") val finishReason: String
)

@Serializable
data class MistralTTSRequest(
    val model: String = "voxtral-mini-tts-latest",
    val input: String,
    @SerialName("voice_id") val voiceId: String = "default",
    @SerialName("response_format") val responseFormat: String = "mp3"
)

@Serializable
data class MistralTranscriptionResponse(
    val text: String
)
