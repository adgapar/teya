package com.teya.agent.persona

import kotlinx.serialization.json.JsonObject

/**
 * Provider-agnostic description of a tool the agent can call. Each brain/provider
 * (MistralClient today, others later) adapts this into its own function-calling shape.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
