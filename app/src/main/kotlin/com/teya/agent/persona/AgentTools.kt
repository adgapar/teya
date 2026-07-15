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
                putJsonObject("notify_family") {
                    put("type", "boolean")
                    put("description", "Whether this is a shared family event worth telling everyone " +
                        "about (an appointment, activity, birthday) vs. a personal reminder/chore " +
                        "nobody else needs to know about ('take out the trash', 'call the plumber'). " +
                        "Defaults to true. When true, every household member with an email on file " +
                        "gets a real calendar invite automatically; set false for personal reminders.")
                }
                putJsonObject("attendees") {
                    put("type", "string")
                    put("description", "Invite ONLY these household members, by name, comma-separated " +
                        "(e.g. 'Mom, Dad') — overrides the invite-everyone default. Use when the " +
                        "request names specific people, not the whole family.")
                }
                putJsonObject("exclude_attendees") {
                    put("type", "string")
                    put("description", "Household members to leave out of the invite-everyone default, " +
                        "comma-separated — e.g. a surprise party where the person it's for shouldn't " +
                        "get the invite. Ignored if 'attendees' is set.")
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

    val logExpense = ToolSpec(
        name = "log_expense",
        description = "Log money spent, e.g. '12 euros for fruit' or 'paid 3.50 for a coffee'. This " +
            "is a budget/expense record, NOT the shopping list — use this whenever an amount of money " +
            "is mentioned, even if the item would also belong on the shopping list.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("amount") {
                    put("type", "number")
                    put("description", "How much was spent, as a plain number, e.g. 12 or 3.5.")
                }
                putJsonObject("item") {
                    put("type", "string")
                    put("description", "What it was for, e.g. 'fruit' or 'coffee'.")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "One of: groceries, dining, transport, utilities, health, " +
                        "household, entertainment, kids, other. Pick the closest fit yourself.")
                }
                putJsonObject("currency") {
                    put("type", "string")
                    put("description", "ISO currency code, e.g. USD, GBP. Omit to use the household's " +
                        "default currency — only set this if a different currency was explicitly stated.")
                }
            }
            putJsonArray("required") { add("amount"); add("item") }
        },
    )

    val queryExpenses = ToolSpec(
        name = "query_expenses",
        description = "Answer 'how much have we spent' questions. Totals and breakdowns are computed " +
            "for you exactly — read the numbers back, never add them up yourself.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("period") {
                    put("type", "string")
                    put("description", "One of: today, week, month, year, all. Defaults to month.")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "Limit to one category (see log_expense). Omit for everything.")
                }
            }
        },
    )

    val deleteExpense = ToolSpec(
        name = "delete_expense",
        description = "Remove a logged expense — a mis-logged entry, or someone says 'undo that'. " +
            "This is the only way to remove one; never call log_expense with a negative amount instead.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("item") {
                    put("type", "string")
                    put("description", "Which expense, matched by what it was for, e.g. 'coffee'. " +
                        "Omit to remove the most recently logged one.")
                }
            }
        },
    )

    val remember = ToolSpec(
        name = "remember",
        description = "Save something to long-term memory about the family so you recall it in future " +
            "conversations. Use it when someone shares a lasting fact, a preference, or a recurring " +
            "routine worth keeping. What you've saved is shown to you each turn under 'What you remember'.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("fact") {
                    put("type", "string")
                    put("description", "The thing to remember, as a short statement, e.g. 'is allergic to peanuts'.")
                }
                putJsonObject("about") {
                    put("type", "string")
                    put("description", "Whom it concerns — a household member's name or nickname (e.g. 'Sam', " +
                        "'Dad'). Omit for a family-wide fact.")
                }
                putJsonObject("category") {
                    put("type", "string")
                    put("description", "One of: fact (a lasting truth), preference (a like/dislike that may " +
                        "change), routine (a recurring habit), episodic (something that happened). Defaults to fact.")
                }
            }
            putJsonArray("required") { add("fact") }
        },
    )

    val forget = ToolSpec(
        name = "forget",
        description = "Remove something from long-term memory when the family asks you to forget it. " +
            "This is the only way to delete a saved memory.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("fact") {
                    put("type", "string")
                    put("description", "What to forget, matched against saved memories, e.g. 'coffee'.")
                }
                putJsonObject("about") {
                    put("type", "string")
                    put("description", "Limit the search to memories about this member. Optional.")
                }
            }
            putJsonArray("required") { add("fact") }
        },
    )

    val searchMemory = ToolSpec(
        name = "search_memory",
        description = "Search your saved family memories for something that is NOT about a specific " +
            "person and isn't already in the 'What you remember' list shown to you — a household fact " +
            "or note the family once asked you to remember (e.g. 'the wifi password', 'where the spare " +
            "key is'). Use it when they ask about something you may have been told before but don't see.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to look for, e.g. 'wifi password' or 'spare key'.")
                }
            }
            putJsonArray("required") { add("query") }
        },
    )

    /** All tools currently exposed to the brain. */
    val all: List<ToolSpec> = listOf(
        placeCall, setTimer, cancelTimer, setAlarm, cancelAlarm, addEvent, getEvents, cancelEvent,
        addToShoppingList, removeFromShoppingList, readShoppingList, clearShoppingList,
        logExpense, queryExpenses, deleteExpense,
        remember, forget, searchMemory,
    )
}
