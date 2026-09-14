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
        description = "Place a phone call to a member of the household.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "The household member to call, by the name or nickname the family uses, e.g. 'Dad', 'Mom', 'Grandma'.")
                }
            }
            putJsonArray("required") { add("name") }
        },
    )

    val sendMessage = ToolSpec(
        name = "send_message",
        description = "Send a text message (SMS) to a member of the household, so they can read it " +
            "wherever they are — e.g. sending the shopping list to whoever is at the shop.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("recipient") {
                    put("type", "string")
                    put("description", "The household member to text, by the name or nickname the family uses, e.g. 'Dad', 'Mom'.")
                }
                putJsonObject("body") {
                    put("type", "string")
                    put("description", "The full text to send, already written the way it should be read on a phone screen.")
                }
            }
            putJsonArray("required") { add("recipient"); add("body") }
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
                putJsonObject("day") {
                    put("type", "string")
                    put("description", "WHICH DAY, in the person's own words: a weekday name " +
                        "('monday', 'next friday'), 'today'/'tomorrow', or a plain date " +
                        "'YYYY-MM-DD'. Do NOT work out the date for a weekday yourself — pass the " +
                        "weekday word and the device resolves it. Getting this wrong is the single " +
                        "most common calendar mistake.")
                }
                putJsonObject("time") {
                    put("type", "string")
                    put("description", "Time of day, e.g. '17:30' or '5:30pm'.")
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
                putJsonObject("until") {
                    put("type", "string")
                    put("description", "Last date a repeating event happens, ISO 'YYYY-MM-DD' (e.g. " +
                        "'until end of July' → the last day of July). Only meaningful with 'repeat' " +
                        "set. Omit for a repeat that just goes on indefinitely — but if the person " +
                        "gave any end point ('until...', 'through...', 'for the next N weeks'), always " +
                        "resolve it into this field rather than leaving the series open-ended.")
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
            putJsonArray("required") { add("title"); add("day"); add("time") }
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
                    put("description", "Range start, ISO 'YYYY-MM-DDTHH:MM'. Defaults to the start " +
                        "of today (so 'what's on today?' includes events earlier in the day).")
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
        description = "Remove an event from the family calendar. This is the ONLY way to cancel " +
            "one — never re-add an event to try to remove it. If several events match the name, " +
            "this does NOT delete them: it returns the list so you can ask which one is meant.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "The event to remove, matched by name, e.g. 'dentist'.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Which one, when several share the name: copy its exact " +
                        "start=... value from the list this tool gave you. Never invent one.")
                }
                putJsonObject("all") {
                    put("type", "boolean")
                    put("description", "Delete EVERY event matching the name. Only pass true when " +
                        "the person has clearly asked to remove all of them (e.g. after you listed " +
                        "them and they said 'yes, all of them'). Never guess this.")
                }
            }
            putJsonArray("required") { add("title") }
        },
    )

    val moveEvent = ToolSpec(
        name = "move_event",
        description = "Change an event that already exists — its time, length, place or name. Use " +
            "this whenever something on the calendar is wrong or needs to shift ('make it half an " +
            "hour later', 'it's at the studio now'). NEVER call add_event to correct an existing " +
            "event: that leaves the wrong one in place and creates a duplicate beside it.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") {
                    put("type", "string")
                    put("description", "The event to change, matched by its CURRENT name.")
                }
                putJsonObject("start") {
                    put("type", "string")
                    put("description", "Which one, when several share the name: copy its exact " +
                        "start=... value from the list this tool gave you. Never invent one.")
                }
                putJsonObject("new_day") {
                    put("type", "string")
                    put("description", "The new day, in the person's own words ('friday', " +
                        "'tomorrow') or 'YYYY-MM-DD'. Leave out to keep the same day. Never work " +
                        "out a weekday's date yourself.")
                }
                putJsonObject("new_time") {
                    put("type", "string")
                    put("description", "The new time of day, e.g. '18:00'. Leave out to keep it.")
                }
                putJsonObject("duration_minutes") {
                    put("type", "integer")
                    put("description", "New length in minutes. Leave out to keep the current one.")
                }
                putJsonObject("location") {
                    put("type", "string")
                    put("description", "New location. Leave out to keep the current one.")
                }
                putJsonObject("new_title") {
                    put("type", "string")
                    put("description", "New name for the event. Leave out to keep the current one.")
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
                putJsonObject("date") {
                    put("type", "string")
                    put("description", "Date it happened, ISO 'YYYY-MM-DD', ONLY if it wasn't today " +
                        "(e.g. 'yesterday I spent...', 'on Monday I paid...') — resolve the relative " +
                        "date against the current date in the live device state. Omit for today.")
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

    /** Not offered over SMS: an inbound number is spoofable, and these two act outside the conversation. */
    val withheldFromText: Set<String> = setOf("place_call", "send_message")

    /** Tool names offered on the text transport. */
    val textTransport: Set<String> by lazy { all.map { it.name }.toSet() - withheldFromText }

    /** All tools currently exposed to the brain. */
    val all: List<ToolSpec> = listOf(
        placeCall, sendMessage, setTimer, cancelTimer, setAlarm, cancelAlarm, addEvent, getEvents, cancelEvent, moveEvent,
        addToShoppingList, removeFromShoppingList, readShoppingList, clearShoppingList,
        logExpense, queryExpenses, deleteExpense,
        remember, forget, searchMemory,
    )
}
