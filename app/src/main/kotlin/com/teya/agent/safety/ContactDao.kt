package com.teya.agent.safety

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ContactDao {
    @Query("SELECT * FROM contact_allowlist")
    suspend fun getAll(): List<Contact>

    @Query("SELECT * FROM contact_allowlist WHERE name LIKE :name LIMIT 1")
    suspend fun findByName(name: String): Contact?

    @Insert
    suspend fun insert(contact: Contact)

    @Query("DELETE FROM contact_allowlist WHERE id = :id")
    suspend fun delete(id: Int)
}
