package com.teya.agent.calendar

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** One calendar event instance (a recurrence is expanded into instances). */
data class CalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val location: String?,
    /** Who organized it — an email address, or a bare local name ("My calendar"). Never blank. */
    val organizer: String,
)

/**
 * The family calendar, on the native [CalendarContract] provider. Hybrid backing (decided): write
 * into a synced account calendar (e.g. Google) if one is present — so events sync to the family's
 * phones and invites can go out — otherwise create a Teya-owned **local** calendar (zero-setup,
 * on-device only). Reads (via Instances) span every calendar on the device regardless — including
 * anything anyone in the world emailed an invite to the household's synced account. [events]
 * (settled fact) and [inboundInvites] (informational only, never fed to the model as fact or
 * wired to a tool call) split on the same trusted-organizer check; see their doc comments.
 *
 * Blocking content-provider calls — invoke off the main thread.
 */
class CalendarManager(private val context: Context) {

    private val resolver = context.contentResolver
    private var cachedCalendarId: Long? = null

    /**
     * Confirmed events overlapping [startMillis, endMillis], soonest first — organized by the
     * household itself or a household member (see [isTrustedOrganizer]). This is what's fed to the
     * model as settled fact ("today's remaining events", [addEvent]'s own writes, etc). Empty on
     * no-permission/failure.
     */
    fun events(startMillis: Long, endMillis: Long, trustedEmails: Set<String>): List<CalendarEvent> =
        readInstances(startMillis, endMillis, trustedEmails)
            .filter { it.second }.map { it.first }.sortedBy { it.beginMillis }

    /**
     * Events in the same window organized by someone **outside** the household — i.e. anyone who
     * knows the household's synced account address emailed it an invite. Never folded into [events]
     * or fed to the model as settled fact: these are surfaced separately, purely informational, so
     * the household can be told about them and choose to act ("yes, add that one") — the choice
     * stays with a person in the room, never with the model reading the invite's own text.
     */
    fun inboundInvites(startMillis: Long, endMillis: Long, trustedEmails: Set<String>): List<CalendarEvent> =
        readInstances(startMillis, endMillis, trustedEmails)
            .filterNot { it.second }.map { it.first }.sortedBy { it.beginMillis }

    /** Reads every calendar on the device (see class doc), pairing each instance with whether [isTrustedOrganizer]. */
    private fun readInstances(
        startMillis: Long,
        endMillis: Long,
        trustedEmails: Set<String>,
    ): List<Pair<CalendarEvent, Boolean>> = try {
        requestSync()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.ORGANIZER,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        val out = mutableListOf<Pair<CalendarEvent, Boolean>>()
        CalendarContract.Instances.query(resolver, projection, startMillis, endMillis)?.use { c ->
            while (c.moveToNext()) {
                val organizer = c.getString(4)
                val accountName = c.getString(5)
                val event = CalendarEvent(
                    title = c.getString(0) ?: "(untitled)",
                    beginMillis = c.getLong(1),
                    endMillis = c.getLong(2),
                    location = c.getString(3)?.takeIf { it.isNotBlank() },
                    organizer = organizer?.takeIf { it.isNotBlank() } ?: "(unknown)",
                )
                out.add(event to isTrustedOrganizer(organizer, accountName, trustedEmails))
            }
        }
        out
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar read permission not granted", e)
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read calendar", e)
        emptyList()
    }

    /**
     * Nudge every synced account to sync the calendar authority right now instead of waiting for
     * Android's own lazy periodic job (can lag hours behind an incoming invite). Fire-and-forget —
     * [ContentResolver.requestSync] is async, so this can't make the read below see the result of
     * its own nudge, only future reads; harmless no-op for any account without a matching sync
     * adapter registered for this authority. Safe to call on every read now that untrusted
     * organizers are filtered out regardless of how fresh the data is.
     *
     * **Known gap** (confirmed live via `adb shell dumpsys account` → "Account visibility"):
     * `AccountManager.accounts` only returns accounts Teya's app has visibility into, and neither
     * household Google account currently grants that — only Google's own first-party apps do (same
     * restriction `ContactsRepository.pickAccount()` already silently falls back around, seeding
     * contacts under a local account instead). So this is a no-op for the accounts that matter today.
     * Kept anyway: harmless, and it starts working for free the moment visibility opens up (no
     * discoverable Settings toggle for it on this device as of this writing — see docs/experiments.md).
     */
    private fun requestSync() {
        try {
            val extras = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            AccountManager.get(context).accounts.forEach { account ->
                ContentResolver.requestSync(account, CalendarContract.AUTHORITY, extras)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request calendar sync", e)
        }
    }

    /**
     * Local/no-account organizers (no "@" — a bare calendar name like "My calendar", or the
     * birthday provider's "local.samsungbirthday") are trusted unconditionally: they can't have
     * arrived via a network invite. An email-shaped organizer must either be the calendar's own
     * synced account (self-organized, e.g. Teya's own [addEvent]) or a household member's address.
     */
    private fun isTrustedOrganizer(organizer: String?, accountName: String?, trustedEmails: Set<String>): Boolean {
        if (organizer.isNullOrBlank() || !organizer.contains("@")) return true
        if (organizer.equals(accountName, ignoreCase = true)) return true
        return trustedEmails.any { it.equals(organizer, ignoreCase = true) }
    }

    /**
     * Create an event. [rrule] non-null makes it recurring (RFC-5545, e.g. "FREQ=WEEKLY"); the
     * weekday of a weekly rule comes from [startMillis]. [attendeeEmails] are added as event
     * attendees — on a synced Google calendar this is what makes Android's own sync adapter send a
     * real email invite to each address; on the local fallback calendar they're just stored, no
     * email goes out. Returns the event id, or null on failure.
     */
    fun addEvent(
        title: String,
        startMillis: Long,
        durationMinutes: Int,
        location: String?,
        rrule: String?,
        attendeeEmails: List<String> = emptyList(),
        reminderMinutes: Int? = null,
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
                if (reminderMinutes != null && reminderMinutes >= 0) {
                    put(CalendarContract.Events.HAS_ALARM, 1)
                }
            }
            val eventId = resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
            if (eventId != null) {
                addAttendees(eventId, attendeeEmails)
                reminderMinutes?.takeIf { it >= 0 }?.let { replaceReminders(eventId, it) }
            }
            eventId
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar write permission not granted", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add event", e)
        null
    }

    /**
     * Google calendars attach the account's default reminders (typically 10 and 30 min) on
     * insert. Wipe those, then write only the minutes we were asked for, or the UI shows the
     * defaults and not the one they named.
     */
    private fun replaceReminders(eventId: Long, minutesBefore: Int) {
        try {
            resolver.delete(
                CalendarContract.Reminders.CONTENT_URI,
                "${CalendarContract.Reminders.EVENT_ID}=?",
                arrayOf(eventId.toString()),
            )
            val uri = resolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, minutesBefore)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
            if (uri == null) Log.w(TAG, "Reminder insert returned null for event $eventId")
            resolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
                ContentValues().apply { put(CalendarContract.Events.HAS_ALARM, 1) },
                null,
                null,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder", e)
        }
    }

    private fun addAttendees(eventId: Long, emails: List<String>) {
        emails.forEach { email ->
            try {
                val values = ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, eventId)
                    put(CalendarContract.Attendees.ATTENDEE_EMAIL, email)
                    put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
                    put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_INVITED)
                }
                resolver.insert(CalendarContract.Attendees.CONTENT_URI, values)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add attendee $email", e)
            }
        }
    }

    /** A stored event row, not an instance: [startMillis] is a series' DTSTART, not its next occurrence. */
    data class EventMatch(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val recurring: Boolean,
    )

    /** Events whose title contains [query], scoped to Teya's own calendar so a search never reaches birthdays. */
    fun findEventsByTitle(query: String): List<EventMatch> = try {
        val calId = targetCalendarId()
        val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.TITLE} LIKE ?" +
            " AND ${CalendarContract.Events.DELETED} = 0"
        // A title's own % or _ would otherwise make this a match-everything query.
        val escaped = query.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        val args = arrayOf(calId.toString(), "%$escaped%")
        val matches = mutableListOf<EventMatch>()
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.RRULE,
            ),
            "$selection ESCAPE '!'", args, "${CalendarContract.Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                matches.add(EventMatch(
                    id = c.getLong(0),
                    title = c.getString(1) ?: "(untitled)",
                    startMillis = c.getLong(2),
                    recurring = !c.getString(3).isNullOrBlank(),
                ))
            }
        }
        matches
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar read permission not granted", e)
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to search events", e)
        emptyList()
    }

    /**
     * The stored event whose [instance] time matches [instanceStartMillis] (a recurrence's
     * DTSTART is the first occurrence, so "this Tuesday" has to go through Instances).
     */
    fun findEventOn(query: String, date: LocalDate): EventMatch? {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return findInstance(query, start, end)
    }

    fun findEventAt(query: String, instanceStartMillis: Long): EventMatch? =
        findInstance(query, instanceStartMillis - 60_000L, instanceStartMillis + 60_000L)

    private fun findInstance(query: String, windowStart: Long, windowEnd: Long): EventMatch? = try {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.RRULE,
        )
        var match: EventMatch? = null
        CalendarContract.Instances.query(resolver, projection, windowStart, windowEnd)?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: continue
                if (!title.contains(query, ignoreCase = true)) continue
                match = EventMatch(
                    id = c.getLong(0),
                    title = title,
                    startMillis = c.getLong(2),
                    recurring = !c.getString(3).isNullOrBlank(),
                )
                break
            }
        }
        match
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve instance", e)
        null
    }

    /** Deletes one row; for a recurring event that is the whole series. */
    fun deleteEvent(id: Long): Boolean = try {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    } catch (e: Exception) {
        Log.e(TAG, "Failed to delete event", e)
        false
    }

    /** Writes only the non-null fields. Updating a recurring event updates the whole series. */
    fun updateEvent(
        id: Long,
        recurring: Boolean,
        newStartMillis: Long?,
        durationMinutes: Int?,
        location: String?,
        title: String?,
        reminderMinutes: Int? = null,
    ): Boolean = try {
        val values = ContentValues().apply {
            newStartMillis?.let {
                put(CalendarContract.Events.DTSTART, it)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            title?.let { put(CalendarContract.Events.TITLE, it) }
            location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            if (recurring) {
                durationMinutes?.let { put(CalendarContract.Events.DURATION, "P${it * 60}S") }
            } else if (durationMinutes != null || newStartMillis != null) {
                // A one-off stores DTEND, not DURATION, so it has to follow DTSTART.
                val start = newStartMillis ?: eventStart(id)
                val minutes = durationMinutes ?: existingDurationMinutes(id) ?: 60
                if (start != null) put(CalendarContract.Events.DTEND, start + minutes * 60_000L)
            }
        }
        val wrote = if (values.size() == 0) reminderMinutes != null
        else resolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values, null, null
        ) > 0
        if (wrote) reminderMinutes?.takeIf { it >= 0 }?.let { replaceReminders(id, it) }
        wrote
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar write permission not granted", e)
        false
    } catch (e: Exception) {
        Log.e(TAG, "Failed to update event", e)
        false
    }

    private fun eventStart(id: Long): Long? = readEventLongs(id)?.first

    private fun existingDurationMinutes(id: Long): Int? = readEventLongs(id)?.let { (start, end) ->
        if (end > start) ((end - start) / 60_000L).toInt() else null
    }

    private fun readEventLongs(id: Long): Pair<Long, Long>? = try {
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND),
            null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read event times", e)
        null
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
