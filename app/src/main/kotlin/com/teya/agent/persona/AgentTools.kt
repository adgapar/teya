package com.teya.agent.persona

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The real-world actions Teya can take. These are the single source of truth for the agent's
 * capabilities — add a tool here (and handle it in the harness actuator) to extend what Teya
 * can do. Keep [TeyaPersona] in sync so the prompt describes what's available.
 */
object AgentTools {

    val placeCall = ToolSpec(
        name = "place_call",
        description = "Place a phone call to one of the family's approved contacts.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The name of the person to call, e.g. 'Dad', 'Mom', 'Grandma'.")
                }
            }
            putJsonArray("required") { add("name") }
        },
    )

    /** All tools currently exposed to the brain. */
    val all: List<ToolSpec> = listOf(placeCall)
}
