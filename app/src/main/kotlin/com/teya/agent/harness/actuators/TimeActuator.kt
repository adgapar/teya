package com.teya.agent.harness.actuators

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import com.teya.agent.brain.ToolCall
import com.teya.agent.timers.TimerManager

/** Teya-owned countdown timers, plus the system Clock's alarms via AlarmClock intents. */
class TimeActuator(
    private val context: Context,
    private val timerManager: TimerManager,
) {
    suspend fun run(tool: ToolCall): String = when (tool.functionName) {
        "set_timer" -> {
            val seconds = tool.arguments["duration_seconds"]?.toIntOrNull()
            if (seconds == null || seconds <= 0) {
                "Could not set the timer — I need a positive duration in seconds."
            } else {
                val label = tool.arguments["label"].orEmpty()
                // Teya-owned (AlarmManager) so it's cancellable and she announces it herself.
                timerManager.start(seconds, label)
                val mins = seconds / 60
                val secs = seconds % 60
                val dur = buildString {
                    if (mins > 0) append("$mins min ")
                    if (secs > 0 || mins == 0) append("$secs sec")
                }.trim()
                "Timer started for $dur" + if (label.isNotBlank()) " ($label)." else "."
            }
        }
        "cancel_timer" -> {
            val cancelled = timerManager.cancel(tool.arguments["label"])
            when {
                cancelled.isEmpty() -> "There's nothing to cancel."
                cancelled.size == 1 -> "Cancelled the ${cancelled[0].label.ifBlank { "timer" }}."
                else -> "Cancelled ${cancelled.size} timers."
            }
        }
        "cancel_alarm" -> {
            val label = tool.arguments["label"]
            val hour = tool.arguments["hour"]?.toIntOrNull()
            val minute = tool.arguments["minute"]?.toIntOrNull() ?: 0
            val all = tool.arguments["all"]?.toBoolean() ?: false
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                when {
                    !label.isNullOrBlank() -> {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_LABEL)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    }
                    hour != null -> {
                        putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    }
                    all -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
                    else -> putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
                }
            }
            if (!startSystemActivity(intent)) {
                "I couldn't reach the clock to cancel the alarm."
            } else when {
                !label.isNullOrBlank() -> "Asked the clock to cancel the $label alarm."
                hour != null -> "Asked the clock to cancel the %02d:%02d alarm.".format(hour, minute)
                all -> "Asked the clock to cancel all alarms."
                else -> "Asked the clock to cancel the next alarm."
            }
        }
        "set_alarm" -> {
            val hour = tool.arguments["hour"]?.toIntOrNull()
            val minute = tool.arguments["minute"]?.toIntOrNull() ?: 0
            if (hour == null || hour !in 0..23 || minute !in 0..59) {
                "Could not set the alarm — I need a valid time (hour 0-23, minute 0-59)."
            } else {
                val label = tool.arguments["label"].orEmpty()
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                }
                if (startSystemActivity(intent)) {
                    "Alarm set for %02d:%02d".format(hour, minute) +
                        if (label.isNotBlank()) " ($label)." else "."
                } else {
                    "I couldn't set the alarm — no clock app handled it."
                }
            }
        }
        else -> "Unknown time tool: ${tool.functionName}"
    }

    /** Fire a system intent (e.g. AlarmClock); false if nothing handled it. */
    private fun startSystemActivity(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to start activity for ${intent.action}", e)
        false
    }

    private companion object { const val TAG = "TimeActuator" }
}
