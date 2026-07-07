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
    // Nullable: assistant messages that carry a tool_call return content = null,
    // which would otherwise crash deserialization into a non-null String.
    val content: String? = null,
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
    // No defaults on purpose: the Json config uses encodeDefaults=false, so default-valued
    // fields are omitted from the body. The REST field is `voice` (not `voice_id`); a request
    // without it returns 400 "Either ref_audio or voice must be provided."
    val model: String,
    val input: String,
    val voice: String,
    @SerialName("response_format") val responseFormat: String
)

@Serializable
data class MistralTranscriptionResponse(
    val text: String
)

@Serializable
data class MistralTTSResponse(
    // /audio/speech returns the audio base64-encoded inside JSON, not raw bytes.
    @SerialName("audio_data") val audioData: String
)
