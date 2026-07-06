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
import java.io.File

class MistralClient(
    private val httpClient: HttpClient,
    private val apiKey: String
) : BrainClient {

    private val baseUrl = "https://api.mistral.ai/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanApiKey = apiKey.replace(Regex("[^\\x20-\\x7E]"), "").trim()

    /**
     * STT: Audio -> Text
     * Uses Voxtral Transcribe 2
     */
    suspend fun transcribe(audioFile: File): String {
        val httpResponse = httpClient.post("$baseUrl/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer $cleanApiKey")
            setBody(MultiPartFormDataContent(
                formData {
                    append("model", "voxtral-mini-latest")
                    append("file", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/mpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    })
                }
            ))
        }

        if (httpResponse.status != HttpStatusCode.OK) {
            val errorBody = httpResponse.bodyAsText()
            Log.e("MistralClient", "STT Error: ${httpResponse.status} - $errorBody")
            return ""
        }

        val response: MistralTranscriptionResponse = httpResponse.body()
        return response.text
    }

    /**
     * LLM: Text -> Decision (Tool call or Speech)
     * This fulfills the BrainClient interface
     */
    override suspend fun processText(input: String): BrainResponse {
        Log.d("MistralClient", "Processing text: $input")
        val httpResponse = httpClient.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $cleanApiKey")
            contentType(ContentType.Application.Json)
            setBody(MistralChatRequest(
                model = "mistral-large-latest",
                messages = listOf(MistralMessage(role = "user", content = input))
            ))
        }

        if (httpResponse.status != HttpStatusCode.OK) {
            val errorBody = httpResponse.bodyAsText()
            Log.e("MistralClient", "LLM Error: ${httpResponse.status} - $errorBody")
            return BrainResponse("I'm sorry, I'm having trouble connecting to my brain. Please check your API key.")
        }

        val response: MistralChatResponse = httpResponse.body()
        val choice = response.choices.firstOrNull() ?: return BrainResponse("I have no words.")
        
        val toolCall = choice.message.toolCalls?.firstOrNull()?.let {
            val args = try {
                json.decodeFromString<Map<String, String>>(it.function.arguments)
            } catch (e: Exception) {
                Log.e("MistralClient", "Failed to parse tool arguments", e)
                emptyMap()
            }
            ToolCall(it.function.name, args)
        }

        return BrainResponse(
            speechResponse = choice.message.content,
            toolCall = toolCall
        )
    }

    /**
     * TTS: Text -> Audio Stream
     * Uses Voxtral TTS
     */
    suspend fun synthesizeSpeech(text: String, onStream: suspend (ByteReadChannel) -> Unit) {
        if (text.isBlank()) return
        
        httpClient.preparePost("$baseUrl/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer $cleanApiKey")
            contentType(ContentType.Application.Json)
            setBody(MistralTTSRequest(input = text))
        }.execute { response ->
            if (response.status == HttpStatusCode.OK) {
                onStream(response.bodyAsChannel())
            } else {
                val errorBody = response.bodyAsText()
                Log.e("MistralClient", "TTS Error: ${response.status} - $errorBody")
            }
        }
    }
}
