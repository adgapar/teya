package com.teya.agent.brain

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object KtorClientFactory {
    fun create(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
            install(Logging) {
                level = LogLevel.INFO
            }
            // A stalled request must not wedge the always-on agent (audit C5). Generous enough
            // for streaming TTS (SSE) and LLM calls, but bounded.
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 30000
            }
            // Voxtral Realtime (barge-in speech detection) — see MistralRealtimeClient.
            install(WebSockets)
        }
    }
}
