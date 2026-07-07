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

    val addEvent = ToolSpec(
        name = "add_event",
        description = "Add an event to the family calendar. Resolve relative dates ('tomorrow', " +
            "'next Tuesday') against the current date/time in the live device state.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "What the event is, e.g. 'Football' or 'Dentist'.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Local start date-time, ISO 'YYYY-MM-DDTHH:MM' (e.g. 2026-07-14T17:30).")
                }
                putJsonObject("duration_minutes") {
                    put("type", "integer")
                    put("description", "How long the event lasts, in minutes. Defaults to 60.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "Where it is, e.g. 'City stadium'. Optional.")
                }
                putJsonObject("repeat") {
                    put("type", "string")
                    put("description", "Recurrence: one of daily, weekly, monthly, yearly, weekdays. " +
                        "For 'every Tuesday' use weekly with a Tuesday start. Omit for a one-off.")
                }
            }
            putJsonArray("required") { add("title"); add("start") }
        },
    )

    val getEvents = ToolSpec(
        name = "get_events",
        description = "Look up calendar events in a date range to answer 'what's on'. Compute the " +
            "range from the live device state (e.g. 'Saturday' → that day 00:00 to 23:59).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Range start, ISO 'YYYY-MM-DDTHH:MM'. Defaults to now.")
                }
                putJsonObject("end") {
                    put("type", "string")
                    put("description", "Range end, ISO 'YYYY-MM-DDTHH:MM'. Defaults to a week ahead.")
                }
            }
        },
    )

    val cancelEvent = ToolSpec(
        name = "cancel_event",
        description = "Remove an event from the family calendar by its title (e.g. 'football'). " +
            "Removes the whole series if it repeats. This is the ONLY way to cancel an event — " +
            "never re-add an event to try to remove it.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "The event to remove, matched by name, e.g. 'dentist'.")
                }
            }
            putJsonArray("required") { add("title") }
        },
    )

    val addToShoppingList = ToolSpec(
        name = "add_to_shopping_list",
        description = "Add one or more items to the family shopping list. Use for 'we need X', " +
            "'add X', 'we're out of X'. Pass several at once comma-separated (e.g. 'milk, eggs, bread').",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "string")
                    put("description", "Item(s) to buy, comma-separated for several.")
                }
            }
            putJsonArray("required") { add("items") }
        },
    )

    val removeFromShoppingList = ToolSpec(
        name = "remove_from_shopping_list",
        description = "Remove one or more items from the shopping list (bought, or added by mistake). " +
            "Comma-separate several. This is the only way to take things off the list.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "string")
                    put("description", "Item(s) to remove, comma-separated for several.")
                }
            }
            putJsonArray("required") { add("items") }
        },
    )

    val readShoppingList = ToolSpec(
        name = "read_shopping_list",
        description = "Read back the current shopping list, e.g. when someone is heading to the shop " +
            "and asks what to buy.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    val clearShoppingList = ToolSpec(
        name = "clear_shopping_list",
        description = "Empty the whole shopping list, e.g. after the shopping is done.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {}
        },
    )

    /** All tools currently exposed to the brain. */
    val all: List<ToolSpec> = listOf(
        placeCall, setTimer, cancelTimer, setAlarm, cancelAlarm, addEvent, getEvents, cancelEvent,
        addToShoppingList, removeFromShoppingList, readShoppingList, clearShoppingList,
    )
}
