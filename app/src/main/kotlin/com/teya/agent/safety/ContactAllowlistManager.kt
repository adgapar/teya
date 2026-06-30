package com.teya.agent.safety

import android.content.Context
import androidx.room.Room

class ContactAllowlistManager(context: Context) {
    private val db = Room.databaseBuilder(
        context.applicationContext,
        TeyaDatabase::class.java, "teya-db"
    ).build()

    private val contactDao = db.contactDao()

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
