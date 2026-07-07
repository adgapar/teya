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

    // NOTE: there is deliberately no get_time tool — the current time and location are injected
    // into the model's context every turn as "live device state" (see HarnessService.buildLiveContext),
    // so the model already knows them and needn't spend a tool round-trip. Ambient, not a tool.

    val setTimer = ToolSpec(
        name = "set_timer",
        description = "Start a countdown timer on the device, e.g. for cooking. Convert the spoken " +
            "duration to whole seconds yourself (10 minutes = 600). Optionally give it a label.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("duration_seconds") {
                    put("type", "integer")
                    put("description", "Timer length in seconds, e.g. 600 for 10 minutes.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Optional name for the timer, e.g. 'pasta'.")
                }
            }
            putJsonArray("required") { add("duration_seconds") }
        },
    )

    val cancelTimer = ToolSpec(
        name = "cancel_timer",
        description = "Cancel a running countdown timer. Give its label to target one specifically; " +
            "omit the label to cancel the only running timer (or all of them if several are running).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Which timer, e.g. 'pasta'. Omit if there's only one.")
                }
            }
        },
    )

    val setAlarm = ToolSpec(
        name = "set_alarm",
        description = "Set an alarm clock for a specific time of day (e.g. a wake-up alarm). Give " +
            "the time on a 24-hour clock; convert AM/PM yourself (7 AM = 7, 9 PM = 21).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "Hour on a 24-hour clock, 0-23.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "Minute, 0-59. Use 0 if none was stated.")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Optional name for the alarm, e.g. 'gym'.")
                }
            }
            putJsonArray("required") { add("hour") }
        },
    )

    val cancelAlarm = ToolSpec(
        name = "cancel_alarm",
        description = "Cancel/dismiss an alarm in the system clock. Target it by label, or by time " +
            "(hour + minute), or set all=true for every alarm; omit everything to cancel the next one.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Alarm label to cancel, e.g. 'gym'.")
                }
                putJsonObject("hour") {
                    put("type", "integer")
                    put("description", "Hour 0-23 to match an alarm by its time.")
                }
                putJsonObject("minute") {
                    put("type", "integer")
                    put("description", "Minute 0-59, paired with hour.")
                }
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "True to cancel all alarms.")
                }
            }
        },
    )

    /** All tools currently exposed to the brain. */
    val all: List<ToolSpec> = listOf(placeCall, setTimer, cancelTimer, setAlarm, cancelAlarm)
}
