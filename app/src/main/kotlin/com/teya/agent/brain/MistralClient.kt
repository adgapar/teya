package com.teya.agent.brain

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

    /**
     * STT: Audio -> Text
     * Uses Voxtral Transcribe 2
     */
    suspend fun transcribe(audioFile: File): String {
        val response: MistralTranscriptionResponse = httpClient.post("$baseUrl/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(MultiPartFormDataContent(
                formData {
                    append("model", "voxtral-mini-latest")
                    append("file", audioFile.readBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "audio/mpeg")
                        append(HttpHeaders.ContentDisposition, "filename=\"${audioFile.name}\"")
                    })
                }
            ))
        }.body()
        return response.text
    }

    /**
     * LLM: Text -> Decision (Tool call or Speech)
     * This fulfills the BrainClient interface
     */
    override suspend fun processText(input: String): BrainResponse {
        val response: MistralChatResponse = httpClient.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(MistralChatRequest(
                model = "mistral-large-latest",
                messages = listOf(MistralMessage(role = "user", content = input))
            ))
        }.body()

        val choice = response.choices.first()
        val toolCall = choice.message.toolCalls?.firstOrNull()?.let {
            val args = try {
                json.decodeFromString<Map<String, String>>(it.function.arguments)
            } catch (e: Exception) {
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
        httpClient.preparePost("$baseUrl/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(MistralTTSRequest(input = text))
        }.execute { response ->
            if (response.status == HttpStatusCode.OK) {
                onStream(response.bodyAsChannel())
            }
        }
    }
}
