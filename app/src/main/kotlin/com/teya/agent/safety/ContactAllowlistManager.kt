package com.teya.agent.safety

import android.content.Context

class ContactAllowlistManager(context: Context) {
    // Shares the one migrated DB instance (v2) — do not build a second, unmigrated one.
    private val contactDao = TeyaDatabase.get(context).contactDao()

    suspend fun isAllowed(name: String): Boolean {
        return contactDao.findByName(name) != null
    }

    suspend fun getPhoneNumber(name: String): String? {
        return contactDao.findByName(name)?.phoneNumber
    }

    suspend fun addContact(name: String, phoneNumber: String, relation: String? = null) {
        contactDao.insert(Contact(name = name, phoneNumber = phoneNumber, relation = relation))
    }
}
