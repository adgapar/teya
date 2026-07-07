package com.teya.agent.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.teya.agent.harness.HarnessService
import java.util.concurrent.atomic.AtomicInteger

/** A countdown timer Teya owns (as opposed to a system Clock timer). */
data class TeyaTimer(val id: Int, val label: String, val endAtMillis: Long)

/**
 * Teya-owned countdown timers, scheduled with the native [AlarmManager]. We own them (rather than
 * firing an `AlarmClock` intent) because the system Clock API cannot cancel a *running* timer —
 * owning the state is what makes cancel / list / time-left possible. When a timer fires, the
 * pending intent re-enters [HarnessService] with [HarnessService.ACTION_TIMER_FIRED] so Teya can
 * announce it in her own voice.
 *
 * The active list is in-memory: if the process is killed the alarm still fires and Teya still
 * announces (the label rides in the intent extras), but she'd lose the ability to list/cancel it.
 * Acceptable for an always-on foreground service; persist to disk later if it proves flaky.
 */
class TimerManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val timers = mutableListOf<TeyaTimer>()
    private val nextId = AtomicInteger(1)

    @Synchronized
    fun start(durationSeconds: Int, label: String): TeyaTimer {
        val timer = TeyaTimer(
            id = nextId.getAndIncrement(),
            label = label.trim(),
            endAtMillis = System.currentTimeMillis() + durationSeconds * 1000L,
        )
        timers.add(timer)
        schedule(timer)
        Log.d(TAG, "Started timer id=${timer.id} label='${timer.label}' in ${durationSeconds}s")
        return timer
    }

    /** Cancel by label (case-insensitive contains); with no label, cancel the only timer, else all. */
    @Synchronized
    fun cancel(label: String?): List<TeyaTimer> {
        purgeExpired()
        val toCancel = when {
            !label.isNullOrBlank() -> timers.filter { it.label.contains(label.trim(), ignoreCase = true) }
            else -> timers.toList() // no label → the only one, or all of them
        }
        toCancel.forEach { alarmManager.cancel(pendingIntent(it)) }
        timers.removeAll(toCancel.toSet())
        Log.d(TAG, "Cancelled ${toCancel.size} timer(s)")
        return toCancel
    }

    /** Active (not-yet-fired) timers, soonest first. */
    @Synchronized
    fun active(): List<TeyaTimer> {
        purgeExpired()
        return timers.sortedBy { it.endAtMillis }
    }

    /** Called when a timer fires so it drops out of the active list. */
    @Synchronized
    fun onFired(id: Int) {
        timers.removeAll { it.id == id }
    }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        timers.removeAll { it.endAtMillis <= now }
    }

    private fun schedule(timer: TeyaTimer) {
        val pi = pendingIntent(timer)
        try {
            // Exact + wake so a cooking timer fires on time even in Doze. Firing puts the app on a
            // temporary allowlist, so re-entering the foreground service is permitted.
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAtMillis, pi)
        } catch (e: SecurityException) {
            Log.w(TAG, "Exact alarm not permitted; falling back to inexact", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timer.endAtMillis, pi)
        }
    }

    private fun pendingIntent(timer: TeyaTimer): PendingIntent {
        val intent = Intent(context, HarnessService::class.java).apply {
            action = HarnessService.ACTION_TIMER_FIRED
            putExtra(HarnessService.EXTRA_TIMER_LABEL, timer.label)
            putExtra(HarnessService.EXTRA_TIMER_ID, timer.id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, timer.id, intent, flags)
        } else {
            PendingIntent.getService(context, timer.id, intent, flags)
        }
    }

    companion object {
        private const val TAG = "TimerManager"
    }
}
