package com.teya.agent.household

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PersonaDao {
    @Query("SELECT * FROM persona ORDER BY name")
    suspend fun getAll(): List<Persona>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: Persona): Long

    @Query("DELETE FROM persona WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memory_entry ORDER BY addedAt DESC")
    suspend fun getAll(): List<MemoryEntry>

    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Query("DELETE FROM memory_entry WHERE id = :id")
    suspend fun delete(id: Int)
}

@Dao
interface ContactExtraDao {
    @Query("SELECT * FROM contact_extra")
    suspend fun getAll(): List<ContactExtra>

    @Query("SELECT * FROM contact_extra WHERE lookupKey = :lookupKey LIMIT 1")
    suspend fun find(lookupKey: String): ContactExtra?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(extra: ContactExtra)

    @Query("DELETE FROM contact_extra WHERE lookupKey = :lookupKey")
    suspend fun delete(lookupKey: String)

    @Query("DELETE FROM contact_extra")
    suspend fun clear()
}
