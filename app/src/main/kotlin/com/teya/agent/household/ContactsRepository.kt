package com.teya.agent.household

import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Household members ↔ native `ContactsContract`. Members live here (not in a private table) so they
 * sync to Google Contacts when the device has an account, stay editable anywhere, and *are* the
 * call list. This class does pure Contacts I/O keyed by the stable Contacts `lookupKey`;
 * [HouseholdManager] layers on the Room augmentation (membership + full alias list).
 *
 * Every method is defensive: Contacts operations can fail (permission revoked, provider hiccups)
 * and a broken roster must never crash the agent — failures are logged and degrade to empty/no-op.
 */
class ContactsRepository(private val context: Context) {
    private val resolver get() = context.contentResolver

    data class ContactFields(
        val first: String,
        val last: String,
        val email: String,
        val phone: String,
        val birthday: String,
    )

    /**
     * Insert [members] as raw contacts under the best account, one batch. Returns each member's
     * resulting Contacts `lookupKey` in input order (null where a row couldn't be resolved).
     */
    suspend fun insertMembers(members: List<Member>): List<String?> = withContext(Dispatchers.IO) {
        if (members.isEmpty()) return@withContext emptyList()
        val (accountName, accountType) = pickAccount()
        Log.d(TAG, "Seeding ${members.size} members under account=${accountName ?: "<local device>"}")

        val ops = ArrayList<ContentProviderOperation>()
        val rawOpIndex = IntArray(members.size)   // batch index of each member's RawContacts insert
        members.forEachIndexed { i, m ->
            rawOpIndex[i] = ops.size
            ops.add(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_NAME, accountName)
                    .withValue(RawContacts.ACCOUNT_TYPE, accountType)
                    .build()
            )
            ops.add(
                dataInsert(rawOpIndex[i], StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(StructuredName.GIVEN_NAME, m.first.ifBlank { null })
                    .withValue(StructuredName.FAMILY_NAME, m.last.ifBlank { null })
                    .build()
            )
            // Nickname mirrors only the first alias (single-valued, transparent in Google Contacts);
            // the full alias list is Teya-private and kept in Room by HouseholdManager.
            m.aliases.firstOrNull()?.takeIf { it.isNotBlank() }?.let { alias ->
                ops.add(
                    dataInsert(rawOpIndex[i], Nickname.CONTENT_ITEM_TYPE)
                        .withValue(Nickname.NAME, alias)
                        .build()
                )
            }
            m.phone.takeIf { it.isNotBlank() }?.let { phone ->
                ops.add(
                    dataInsert(rawOpIndex[i], Phone.CONTENT_ITEM_TYPE)
                        .withValue(Phone.NUMBER, phone)
                        .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                        .build()
                )
            }
            m.email.takeIf { it.isNotBlank() }?.let { email ->
                ops.add(
                    dataInsert(rawOpIndex[i], Email.CONTENT_ITEM_TYPE)
                        .withValue(Email.ADDRESS, email)
                        .withValue(Email.TYPE, Email.TYPE_HOME)
                        .build()
                )
            }
            m.birthday.takeIf { it.isNotBlank() }?.let { bday ->
                ops.add(
                    dataInsert(rawOpIndex[i], Event.CONTENT_ITEM_TYPE)
                        .withValue(Event.START_DATE, bday)          // ISO "YYYY-MM-DD"
                        .withValue(Event.TYPE, Event.TYPE_BIRTHDAY)
                        .build()
                )
            }
        }

        try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            members.indices.map { i ->
                val rawUri = results.getOrNull(rawOpIndex[i])?.uri ?: return@map null
                lookupKeyForRawContact(ContentUris.parseId(rawUri))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed members into Contacts", e)
            members.map { null }
        }
    }

    /**
     * Read identity fields for a contact by [lookupKey]; null if it no longer exists. Queries the
     * generic DATA1/2/3 columns (not the typed aliases like Email.ADDRESS + Phone.NUMBER, which both
     * resolve to `data1` and would collide in one projection) and interprets them by MIMETYPE.
     */
    suspend fun read(lookupKey: String): ContactFields? = withContext(Dispatchers.IO) {
        val contactId = resolveContactId(lookupKey) ?: return@withContext null
        var first = ""; var last = ""; var email = ""; var phone = ""; var birthday = ""
        try {
            resolver.query(
                Data.CONTENT_URI,
                arrayOf(Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3),
                "${Data.CONTACT_ID} = ?",
                arrayOf(contactId.toString()),
                null,
            )?.use { c ->
                val mimeIdx = c.getColumnIndexOrThrow(Data.MIMETYPE)
                val d1 = c.getColumnIndexOrThrow(Data.DATA1)  // email / phone / event start date
                val d2 = c.getColumnIndexOrThrow(Data.DATA2)  // given name / event type
                val d3 = c.getColumnIndexOrThrow(Data.DATA3)  // family name
                while (c.moveToNext()) {
                    when (c.getString(mimeIdx)) {
                        StructuredName.CONTENT_ITEM_TYPE -> {
                            first = c.getString(d2).orEmpty()
                            last = c.getString(d3).orEmpty()
                        }
                        Email.CONTENT_ITEM_TYPE -> if (email.isBlank()) email = c.getString(d1).orEmpty()
                        Phone.CONTENT_ITEM_TYPE -> if (phone.isBlank()) phone = c.getString(d1).orEmpty()
                        Event.CONTENT_ITEM_TYPE ->
                            if (birthday.isBlank() && c.getInt(d2) == Event.TYPE_BIRTHDAY)
                                birthday = c.getString(d1).orEmpty()
                    }
                }
            }
            ContactFields(first, last, email, phone, birthday)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read contact $lookupKey", e)
            null
        }
    }

    /** Delete a contact (all its raw contacts) by [lookupKey]. No-op if already gone. */
    suspend fun delete(lookupKey: String) = withContext(Dispatchers.IO) {
        try {
            val lookupUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey)
            resolver.delete(lookupUri, null, null)
            Unit
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete contact $lookupKey", e)
        }
    }

    private fun dataInsert(rawRef: Int, mimeType: String) =
        ContentProviderOperation.newInsert(Data.CONTENT_URI)
            .withValueBackReference(Data.RAW_CONTACT_ID, rawRef)
            .withValue(Data.MIMETYPE, mimeType)

    /** Google account (for Contacts sync) if present, else (null, null) = local device account. */
    private fun pickAccount(): Pair<String?, String?> = try {
        AccountManager.get(context).getAccountsByType("com.google").firstOrNull()
            ?.let { it.name to it.type } ?: (null to null)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't enumerate accounts; writing local contacts", e)
        null to null
    }

    private fun resolveContactId(lookupKey: String): Long? = try {
        val lookupUri = Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, lookupKey)
        Contacts.lookupContact(resolver, lookupUri)?.let { ContentUris.parseId(it) }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve lookupKey $lookupKey", e)
        null
    }

    private fun lookupKeyForRawContact(rawContactId: Long): String? = try {
        resolver.query(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId),
            arrayOf(RawContacts.CONTACT_ID),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val contactId = c.getLong(c.getColumnIndexOrThrow(RawContacts.CONTACT_ID))
            resolver.query(
                ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId),
                arrayOf(Contacts.LOOKUP_KEY),
                null, null, null,
            )?.use { cc ->
                if (cc.moveToFirst()) cc.getString(cc.getColumnIndexOrThrow(Contacts.LOOKUP_KEY)) else null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve lookupKey for raw contact $rawContactId", e)
        null
    }

    companion object {
        private const val TAG = "ContactsRepository"
    }
}
