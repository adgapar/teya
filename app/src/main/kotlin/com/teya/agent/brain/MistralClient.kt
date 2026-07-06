package com.teya.agent.brain

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

class MistralClient(
    private val httpClient: HttpClient,
    private val apiKey: String
) : BrainClient {

    private val baseUrl = "https://api.mistral.ai/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanApiKey = apiKey.replace(Regex("[^\\x20-\\x7E]"), "").trim()

    private val systemPrompt = """
        You are Teya, a helpful family AI agent living on a dedicated home device.
        Your primary job is to help family members make phone calls to approved contacts.
        
        When a user asks to "Call [Name]", you MUST use the 'place_call' tool.
        Keep your spoken responses very short and natural, suitable for a voice interface.
        Example: "Sure, calling Dad now."
    """.trimIndent()

    private val tools = listOf(
        MistralTool(
            function = MistralFunctionDef(
                name = "place_call",
                description = "Initiates a cellular phone call to a named contact",
                parameters = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "The name of the person to call (e.g., 'Dad', 'Mom')")
                        }
                    }
                    putJsonArray("required") {
                        add("name")
                    }
                }
            )
        )
    )

    suspend fun transcribe(audioFile: File): String {
        val mime = if (audioFile.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"
        val httpResponse = httpClient.post("${baseUrl}/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            setBody(MultiPartFormDataContent(
                formData {
                    append("model", "voxtral-mini-latest")
                    append("file", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, mime)
                        append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    })
                }
            ))
        }

        if (httpResponse.status != HttpStatusCode.OK) {
            val errorBody = httpResponse.bodyAsText()
            Log.e("MistralClient", "STT Error: ${httpResponse.status} - ${errorBody}")
            return ""
        }

        val response: MistralTranscriptionResponse = httpResponse.body()
        return response.text
    }

    override suspend fun processText(input: String): BrainResponse {
        Log.d("MistralClient", "Processing text: ${input}")
        val httpResponse = httpClient.post("${baseUrl}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            contentType(ContentType.Application.Json)
            setBody(MistralChatRequest(
                model = "mistral-large-latest",
                messages = listOf(
                    MistralMessage(role = "system", content = systemPrompt),
                    MistralMessage(role = "user", content = input)
                ),
                tools = tools,
                toolChoice = "auto"
            ))
        }

        if (httpResponse.status != HttpStatusCode.OK) {
            val errorBody = httpResponse.bodyAsText()
            Log.e("MistralClient", "LLM Error: ${httpResponse.status} - ${errorBody}")
            return BrainResponse("I'm sorry, I'm having trouble connecting to my brain.")
        }

        val response: MistralChatResponse = httpResponse.body()
        val choice = response.choices.firstOrNull() ?: return BrainResponse("I have no words.")
        
        val toolCall = choice.message.toolCalls?.firstOrNull()?.let {
            Log.d("MistralClient", "Tool call detected: ${it.function.name} with ${it.function.arguments}")
            val args = try {
                json.decodeFromString<Map<String, String>>(it.function.arguments)
            } catch (e: Exception) {
                Log.e("MistralClient", "Failed to parse tool arguments", e)
                emptyMap()
            }
            ToolCall(it.function.name, args)
        }

        return BrainResponse(
            speechResponse = choice.message.content ?: "",
            toolCall = toolCall
        )
    }

    suspend fun synthesizeSpeech(text: String, onStream: suspend (ByteReadChannel) -> Unit) {
        if (text.isBlank()) return
        Log.d("MistralClient", "Synthesizing: ${text}")
        
        httpClient.preparePost("${baseUrl}/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            contentType(ContentType.Application.Json)
            setBody(MistralTTSRequest(input = text))
        }.execute { response ->
            if (response.status == HttpStatusCode.OK) {
                onStream(response.bodyAsChannel())
            } else {
                val errorBody = response.bodyAsText()
                Log.e("MistralClient", "TTS Error: ${response.status} - ${errorBody}")
            }
        }
    }
}
