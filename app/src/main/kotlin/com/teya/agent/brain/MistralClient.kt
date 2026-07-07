package com.teya.agent.brain

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import com.teya.agent.persona.ToolSpec
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MistralClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val systemPrompt: String,
    tools: List<ToolSpec>
) : BrainClient {

    private val baseUrl = "https://api.mistral.ai/v1"
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanApiKey = apiKey.replace(Regex("[^\\x20-\\x7E]"), "").trim()
    // "Marie - Happy" (fr_fr, female) — warm, radiant tone (a light French accent over English).
    // The `voice` field accepts slug or id (both verified). See docs/mistral-voices.md for all 30.
    // TODO: make this user-configurable in a Settings voice picker.
    private val ttsVoice = "fr_marie_happy"

    // Persona (systemPrompt) and capabilities (tools) live outside this provider client —
    // see com.teya.agent.persona. Here we only adapt the provider-agnostic tool specs into
    // Mistral's function-calling shape.
    private val mistralTools = tools.map {
        MistralTool(function = MistralFunctionDef(it.name, it.description, it.parameters))
    }

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

    override suspend fun processText(history: List<ChatMessage>): BrainResponse {
        Log.d("MistralClient", "Processing ${history.size} message(s)")
        val messages = ArrayList<MistralMessage>(history.size + 1)
        messages.add(MistralMessage(role = "system", content = systemPrompt))
        history.forEach { messages.add(MistralMessage(role = it.role, content = it.content)) }
        val httpResponse = httpClient.post("${baseUrl}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            contentType(ContentType.Application.Json)
            setBody(MistralChatRequest(
                model = "mistral-large-latest",
                messages = messages,
                tools = mistralTools,
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

    /**
     * Synthesize speech and return decoded mp3 bytes, or null on failure. /audio/speech returns
     * the audio base64-encoded inside a JSON envelope (not raw bytes), so we parse and decode it
     * here and return the bytes for in-memory playback (no temp file on disk).
     * TODO: streaming — POST stream:true, response_format:pcm, parse SSE speech.audio.delta events
     * (base64 float32 LE 24kHz mono) into an AudioTrack for lower latency.
     */
    suspend fun synthesizeSpeech(text: String): ByteArray? {
        if (text.isBlank()) return null
        Log.d("MistralClient", "Synthesizing: ${text}")

        val response = httpClient.post("${baseUrl}/audio/speech") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            contentType(ContentType.Application.Json)
            setBody(MistralTTSRequest(
                model = "voxtral-mini-tts-latest",
                input = text,
                voice = ttsVoice,
                responseFormat = "mp3"
            ))
        }
        if (response.status != HttpStatusCode.OK) {
            Log.e("MistralClient", "TTS Error: ${response.status} - ${response.bodyAsText()}")
            logAvailableVoices()
            return null
        }
        return try {
            Base64.decode(response.body<MistralTTSResponse>().audioData, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e("MistralClient", "Failed to decode TTS audio", e)
            null
        }
    }

    /**
     * Low-latency streaming TTS. POSTs stream=true + response_format=pcm and reads the SSE
     * response, invoking [onChunk] with each decoded PCM chunk (float32, 24 kHz, mono) as it
     * arrives — so playback can start ~0.8s in instead of waiting for the whole clip. Returns
     * true if any audio was streamed, false on failure (caller can fall back to the mp3 path).
     */
    suspend fun streamSpeechPcm(text: String, onChunk: suspend (FloatArray) -> Unit): Boolean {
        if (text.isBlank()) return false
        Log.d("MistralClient", "Streaming TTS: ${text}")
        var gotAudio = false
        try {
            httpClient.preparePost("${baseUrl}/audio/speech") {
                header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(MistralTTSStreamRequest(
                    model = "voxtral-mini-tts-latest",
                    input = text,
                    voice = ttsVoice,
                    responseFormat = "pcm",
                    stream = true
                ))
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    Log.e("MistralClient", "TTS stream error: ${response.status} - ${response.bodyAsText()}")
                    logAvailableVoices()
                    return@execute
                }
                val channel = response.bodyAsChannel()
                var event = ""
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    when {
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            if (event == "speech.audio.done") break
                            if (event == "speech.audio.delta") {
                                val data = line.removePrefix("data:").trim()
                                val b64 = try {
                                    json.decodeFromString<MistralTTSDelta>(data).audioData
                                } catch (e: Exception) {
                                    null
                                }
                                if (!b64.isNullOrEmpty()) {
                                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                                    val floats = FloatArray(bytes.size / 4)
                                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                                        .asFloatBuffer().get(floats)
                                    onChunk(floats)
                                    gotAudio = true
                                }
                            }
                        }
                        line.isEmpty() -> event = ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MistralClient", "TTS streaming failed", e)
        }
        return gotAudio
    }

    /** Fire a cheap request to warm the TLS/connection pool, so the first real call isn't slow. */
    suspend fun warmUp() {
        try {
            httpClient.get("${baseUrl}/models") {
                header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            }
            Log.d("MistralClient", "Connection warmed")
        } catch (e: Exception) {
            Log.d("MistralClient", "Warmup failed (ignored)", e)
        }
    }

    /** On a TTS failure, dump the account's available voices so we can pick a valid `voice`. */
    private suspend fun logAvailableVoices() {
        try {
            val resp = httpClient.get("${baseUrl}/audio/voices") {
                header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            }
            Log.d("MistralClient", "Available voices (${resp.status}): ${resp.bodyAsText()}")
        } catch (e: Exception) {
            Log.e("MistralClient", "Failed to list voices", e)
        }
    }
}
