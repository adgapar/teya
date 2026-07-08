package com.teya.agent.brain

import android.util.Base64
import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import com.teya.agent.persona.ToolSpec
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    // Deliberately the small model, not the largest available — cheap and quick, matching a
    // home-appliance voice loop's latency needs over maximal reasoning power (see README).
    private val chatModel = "mistral-small-latest"
    // "Marie - Happy" (fr_fr, female) — warm, radiant tone (a light French accent over English).
    // The `voice` field accepts slug or id (both verified). See docs/mistral-voices.md for all 30.
    // TODO: make this user-configurable in a Settings voice picker.
    private val ttsVoice = "fr_marie_happy"
    // Voxtral Realtime (barge-in) — see detectBargeInSpeech. Delay is the fast end of Mistral's
    // documented 240ms-2400ms range: barge-in latency matters more than transcript accuracy here.
    private val realtimeModel = "voxtral-mini-transcribe-realtime-2602"
    private val realtimeTargetDelayMs = 240

    // Persona (systemPrompt) and capabilities (tools) live outside this provider client —
    // see com.teya.agent.persona. Here we only adapt the provider-agnostic tool specs into
    // Mistral's function-calling shape.
    private val mistralTools = tools.map {
        MistralTool(function = MistralFunctionDef(it.name, it.description, it.parameters))
    }

    /**
     * [contextBias] maps to Mistral's `context_bias` multipart field (array of bias terms), sent
     * as one repeated form part per term — unverified against Mistral's exact wire format, since
     * their docs don't show a raw example. No `language` field: it only accepts a single code, and
     * a multi-language household can't be reduced to one without hurting the others' accuracy.
     */
    suspend fun transcribe(audioFile: File, contextBias: List<String> = emptyList()): String {
        val mime = if (audioFile.extension.equals("wav", ignoreCase = true)) "audio/wav" else "audio/mpeg"
        val httpResponse = httpClient.post("${baseUrl}/audio/transcriptions") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            setBody(MultiPartFormDataContent(
                formData {
                    append("model", "voxtral-mini-latest")
                    contextBias.forEach { term -> append("context_bias", term) }
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

    override suspend fun processText(history: List<ChatMessage>, liveContext: String?): BrainResponse {
        Log.d("MistralClient", "Processing ${history.size} message(s)")
        val messages = buildMistralMessages(history, liveContext)
        val httpResponse = httpClient.post("${baseUrl}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
            contentType(ContentType.Application.Json)
            setBody(MistralChatRequest(
                model = chatModel,
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
        
        // Execute EVERY tool the model asked for (parallel tool-calling), not just the first.
        val toolCalls = choice.message.toolCalls.orEmpty().map {
            Log.d("MistralClient", "Tool call detected: ${it.function.name} with ${it.function.arguments}")
            ToolCall(it.id, it.function.name, parseToolArgs(it.function.arguments))
        }

        return BrainResponse(
            speechResponse = choice.message.content ?: "",
            toolCalls = toolCalls
        )
    }

    /** Build the Mistral message list from our provider-agnostic history + live device context. */
    private fun buildMistralMessages(history: List<ChatMessage>, liveContext: String?): List<MistralMessage> {
        val messages = ArrayList<MistralMessage>(history.size + 1)
        // Fold live device state (time/location) into the system message so it's authoritative
        // ground truth for the model — no tool round-trip needed to learn "now" or "where".
        val system = if (liveContext.isNullOrBlank()) systemPrompt else "$systemPrompt\n\n$liveContext"
        messages.add(MistralMessage(role = "system", content = system))
        history.forEach { m ->
            messages.add(MistralMessage(
                role = m.role,
                content = m.content,
                toolCalls = m.toolCalls?.map { tc ->
                    MistralToolCall(
                        id = tc.id,
                        type = "function",
                        // Mistral wants arguments as a JSON string; re-encode the map we parsed.
                        function = MistralFunctionCall(tc.functionName, json.encodeToString(tc.arguments)),
                    )
                },
                toolCallId = m.toolCallId,
                name = m.name,
            ))
        }
        return messages
    }

    /**
     * Parse a Mistral function-arguments JSON string into a flat String map. Values are flattened
     * to strings so numeric/boolean args (e.g. duration_seconds: 600) survive — decoding straight
     * into Map<String,String> would reject a non-string value and drop the whole map.
     */
    private fun parseToolArgs(argsJson: String): Map<String, String> = try {
        json.decodeFromString<JsonObject>(argsJson.ifBlank { "{}" }).mapValues { (_, v) ->
            (v as? JsonPrimitive)?.content ?: v.toString()
        }
    } catch (e: Exception) {
        Log.e("MistralClient", "Failed to parse tool arguments: $argsJson", e)
        emptyMap()
    }

    /** Accumulates one streamed tool call across chunks (id/name arrive once, arguments in pieces). */
    private class ToolCallAcc {
        var id: String? = null
        var name: String? = null
        val args = StringBuilder()
    }

    /**
     * Streaming chat: reads the SSE `chat.completion.chunk` stream (mirrors [streamSpeechPcm]'s raw
     * channel read). Appends `delta.content` and invokes [onText] with the text so far on each
     * token; accumulates `delta.tool_calls` fragments by index. Returns the completed reply text +
     * any tool calls, so the harness's tool loop is unchanged.
     */
    override suspend fun streamChat(
        history: List<ChatMessage>,
        liveContext: String?,
        onText: suspend (String) -> Unit,
    ): BrainResponse {
        Log.d("MistralClient", "Streaming ${history.size} message(s)")
        val messages = buildMistralMessages(history, liveContext)
        val content = StringBuilder()
        val toolAccs = sortedMapOf<Int, ToolCallAcc>()  // keyed by tool_call index
        var gotAnything = false
        var errored = false
        try {
            httpClient.preparePost("${baseUrl}/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer ${cleanApiKey}")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(MistralChatStreamRequest(
                    model = chatModel,
                    messages = messages,
                    tools = mistralTools,
                    toolChoice = "auto",
                    stream = true,
                ))
            }.execute { response ->
                if (response.status != HttpStatusCode.OK) {
                    Log.e("MistralClient", "LLM stream error: ${response.status} - ${response.bodyAsText()}")
                    errored = true
                    return@execute
                }
                val channel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val chunk = try {
                        json.decodeFromString<MistralChatChunk>(data)
                    } catch (e: Exception) {
                        Log.e("MistralClient", "Bad chat chunk: $data", e)
                        continue
                    }
                    val delta = chunk.choices.firstOrNull()?.delta ?: continue
                    delta.content?.let { piece ->
                        if (piece.isNotEmpty()) {
                            content.append(piece)
                            gotAnything = true
                            onText(content.toString())
                        }
                    }
                    delta.toolCalls?.forEach { tc ->
                        val acc = toolAccs.getOrPut(tc.index) { ToolCallAcc() }
                        tc.id?.let { acc.id = it }
                        tc.function?.name?.let { acc.name = it }
                        tc.function?.arguments?.let { acc.args.append(it) }
                        gotAnything = true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MistralClient", "streamChat failed", e)
            errored = true
        }

        val toolCalls = toolAccs.values.mapNotNull { acc ->
            val name = acc.name ?: return@mapNotNull null
            Log.d("MistralClient", "Streamed tool call: $name(${acc.args})")
            ToolCall(acc.id ?: "", name, parseToolArgs(acc.args.toString()))
        }
        if (!gotAnything && errored) {
            return BrainResponse("I'm sorry, I'm having trouble connecting to my brain.")
        }
        return BrainResponse(speechResponse = content.toString(), toolCalls = toolCalls)
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

    /**
     * Real barge-in detection: streams mic audio to Voxtral Realtime (a genuine streaming STT
     * model over WebSocket — `voxtral-mini-transcribe-realtime-2602`) while Teya is
     * thinking/speaking, and calls [onSpeechDetected] the first time the server recognizes actual
     * words. This is deliberately semantic, not a loudness/VAD guess: the caller (VoicePipeline)
     * feeds this the same raw 16kHz mono int16 chunks the wake-word engine already captures, only
     * while armed. Best-effort — any connection/protocol error just means no interruption fires
     * this turn (logged, not thrown); [audioChunks] closing (harness disarming) ends the session
     * normally.
     *
     * Wire protocol has no public doc beyond the Python SDK's high-level call — reverse-engineered
     * from mistralai/client-python (see MistralModels.kt's RealtimeXxx types for the source note).
     */
    suspend fun detectBargeInSpeech(
        audioChunks: ReceiveChannel<ByteArray>,
        onSpeechDetected: suspend () -> Unit,
    ) {
        try {
            httpClient.webSocket(
                urlString = "wss://api.mistral.ai/v1/audio/transcriptions/realtime?model=$realtimeModel",
                request = { header(HttpHeaders.Authorization, "Bearer $cleanApiKey") }
            ) {
                if (!awaitSessionCreated()) return@webSocket
                sendRealtimeJson(
                    RealtimeSessionUpdateMessage(
                        type = "session.update",
                        session = RealtimeSessionUpdatePayload(
                            audioFormat = RealtimeAudioFormat(encoding = "pcm_s16le", sampleRate = 16000),
                            targetStreamingDelayMs = realtimeTargetDelayMs,
                        )
                    )
                )

                var detected = false
                coroutineScope {
                    val sender = launch {
                        var sent = 0
                        for (chunk in audioChunks) {
                            val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                            // Dump payload heads at the start AND mid-stream — the first chunk of
                            // an armed window often lands right on AudioRecord's post-restart
                            // warm-up silence (expected, not a bug); a mid-stream chunk still being
                            // all-zero after 8s would mean the capture itself carries no signal.
                            if (sent == 0 || sent == 100) {
                                Log.d("MistralClient", "Barge-in: chunk #$sent raw bytes=${chunk.size} b64Len=${b64.length} b64Head=${b64.take(40)}")
                            }
                            sendRealtimeJson(
                                RealtimeInputAudioAppend(
                                    type = "input_audio.append",
                                    audio = b64,
                                )
                            )
                            // Log periodically, not just at the end — a barge-in session normally
                            // ends by cancellation (turn finishes), which skips code after the loop.
                            if (++sent % 25 == 0) Log.d("MistralClient", "Barge-in: sent $sent audio chunks so far")
                        }
                        Log.d("MistralClient", "Barge-in: sent $sent audio chunks total")
                        runCatching { sendRealtimeJson(RealtimeInputAudioFlush(type = "input_audio.flush")) }
                        runCatching { sendRealtimeJson(RealtimeInputAudioEnd(type = "input_audio.end")) }
                    }
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val text = frame.readText()
                        val envelope = try {
                            json.decodeFromString<RealtimeEventEnvelope>(text)
                        } catch (e: Exception) {
                            Log.d("MistralClient", "Barge-in: unparseable event: $text")
                            continue
                        }
                        // Log every event verbatim — cheap, and the only way to see what Mistral is
                        // actually hearing (language detection, segments, etc.) instead of flying blind.
                        Log.d("MistralClient", "Barge-in event: type=${envelope.type} text=${envelope.text}")
                        when (envelope.type) {
                            "transcription.text.delta", "transcription.segment" ->
                                if (!detected && !envelope.text.isNullOrBlank()) {
                                    detected = true
                                    Log.d("MistralClient", "Barge-in: recognized speech (\"${envelope.text}\")")
                                    onSpeechDetected()
                                }
                            "error" -> {
                                Log.w("MistralClient", "Realtime barge-in error: $text")
                                break
                            }
                            "transcription.done" -> break
                        }
                    }
                    sender.cancel()
                }
            }
        } catch (e: Exception) {
            Log.d("MistralClient", "Realtime barge-in session ended: ${e.message}")
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitSessionCreated(): Boolean {
        for (frame in incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val envelope = try {
                json.decodeFromString<RealtimeEventEnvelope>(text)
            } catch (e: Exception) {
                Log.d("MistralClient", "Barge-in: unparseable handshake event: $text")
                continue
            }
            when (envelope.type) {
                "session.created" -> {
                    Log.d("MistralClient", "Barge-in: realtime session created")
                    return true
                }
                "error" -> {
                    Log.e("MistralClient", "Realtime handshake error: $text")
                    return false
                }
            }
        }
        return false
    }

    private suspend inline fun <reified T> DefaultClientWebSocketSession.sendRealtimeJson(message: T) {
        send(Frame.Text(json.encodeToString(message)))
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
