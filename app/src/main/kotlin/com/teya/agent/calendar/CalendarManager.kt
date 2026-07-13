package com.teya.agent.calendar

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.util.Log
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
            val eventId = resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) }
            if (eventId != null) addAttendees(eventId, attendeeEmails)
            eventId
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Calendar write permission not granted", e)
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to add event", e)
        null
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
