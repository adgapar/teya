package com.teya.agent.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.util.TimeZone

/** One calendar event instance (a recurrence is expanded into instances). */
data class CalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val location: String?,
)

/**
 * The family calendar, on the native [CalendarContract] provider. Hybrid backing (decided): write
 * into a synced account calendar (e.g. Google) if one is present — so events sync to the family's
 * phones and invites can go out — otherwise create a Teya-owned **local** calendar (zero-setup,
 * on-device only). Reads (via Instances) span every calendar on the device regardless.
 *
 * Blocking content-provider calls — invoke off the main thread.
 */
class CalendarManager(private val context: Context) {

    private val resolver = context.contentResolver
    private var cachedCalendarId: Long? = null

    /** Events overlapping [startMillis, endMillis], soonest first. Empty on no-permission/failure. */
    fun events(startMillis: Long, endMillis: Long): List<CalendarEvent> = try {
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<CalendarEvent>()
        CalendarContract.Instances.query(resolver, projection, startMillis, endMillis)?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    CalendarEvent(
                        title = c.getString(0) ?: "(untitled)",
                        beginMillis = c.getLong(1),
                        endMillis = c.getLong(2),
                        location = c.getString(3)?.takeIf { it.isNotBlank() },
                    )
                )
            }
        }
        out.sortedBy { it.beginMillis }
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar read permission not granted", e)
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read calendar", e)
        emptyList()
    }

    /**
     * Create an event. [rrule] non-null makes it recurring (RFC-5545, e.g. "FREQ=WEEKLY"); the
     * weekday of a weekly rule comes from [startMillis]. Returns the event id, or null on failure.
     */
    fun addEvent(
        title: String,
        startMillis: Long,
        durationMinutes: Int,
        location: String?,
        rrule: String?,
    ): Long? = try {
        val calId = targetCalendarId()
        if (calId < 0) {
            Log.e(TAG, "No writable calendar available")
            null
        } else {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                if (rrule != null) {
                    // A recurring event needs DURATION, not DTEND (RFC form "P<seconds>S").
                    put(CalendarContract.Events.RRULE, rrule)
                    put(CalendarContract.Events.DURATION, "P${durationMinutes * 60}S")
                } else {
                    put(CalendarContract.Events.DTEND, startMillis + durationMinutes * 60_000L)
                }
            }
            resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar write permission not granted", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add event", e)
        null
    }

    /**
     * Delete events whose title contains [query] (case-insensitive), scoped to Teya's own target
     * calendar so we never touch birthdays/other apps' calendars. Deleting a recurring event's row
     * removes the whole series. Returns the titles removed.
     */
    fun deleteEventsByTitle(query: String): List<String> = try {
        val calId = targetCalendarId()
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} LIKE ?"
        val args = arrayOf(calId.toString(), "%$query%")
        val titles = mutableListOf<String>()
        resolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events.TITLE), selection, args, null)
            ?.use { c -> while (c.moveToNext()) titles.add(c.getString(0) ?: "(untitled)") }
        if (titles.isNotEmpty()) resolver.delete(CalendarContract.Events.CONTENT_URI, selection, args)
        titles
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar write permission not granted", e)
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete events", e)
        emptyList()
    }

    /** A synced (e.g. Google) writable calendar if present; else a Teya-owned local one; cached. */
    private fun targetCalendarId(): Long {
        cachedCalendarId?.let { return it }
        val id = findWritableCalendar() ?: createLocalCalendar()
        cachedCalendarId = id
        Log.d(TAG, "Target calendar id=$id")
        return id
    }

    private fun findWritableCalendar(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            var googleId: Long? = null
            var anyId: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val type = c.getString(1)
                val isPrimary = c.getInt(2) == 1
                if (anyId == null) anyId = id
                if (type == "com.google" && (googleId == null || isPrimary)) googleId = id
            }
            return googleId ?: anyId
        }
        return null
    }

    private fun createLocalCalendar(): Long {
        val account = "Teya"
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, account)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Teya Family")
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3F51B5.toInt())
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        return try {
            resolver.insert(uri, values)?.let { ContentUris.parseId(it) } ?: -1L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local calendar", e)
            -1L
        }
    }

    companion object {
        private const val TAG = "CalendarManager"
    }
}
