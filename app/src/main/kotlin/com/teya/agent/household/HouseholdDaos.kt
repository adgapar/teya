package com.teya.agent.household

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

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

    /** Memories about one subject (a member's lookupKey / persona id) — persona-block assembly. */
    @Query("SELECT * FROM memory_entry WHERE subjectKey = :subjectKey ORDER BY strength DESC, addedAt DESC")
    suspend fun bySubject(subjectKey: String): List<MemoryEntry>

    /** HOT memories — the ones assembled into context every turn. */
    @Query("SELECT * FROM memory_entry WHERE tier = 'HOT' ORDER BY strength DESC, addedAt DESC")
    suspend fun hot(): List<MemoryEntry>

    /** All general-pool rows (not about a specific person) — the RAG search set (brute-force cosine). */
    @Query("SELECT * FROM memory_entry WHERE subjectType = 'GENERAL'")
    suspend fun general(): List<MemoryEntry>

    @Insert
    suspend fun insert(entry: MemoryEntry): Long

    @Update
    suspend fun update(entry: MemoryEntry)

    /** Reinforcement: bump strength + stamp the access time when a memory is used ("use it or lose it"). */
    @Query("UPDATE memory_entry SET strength = :strength, lastAccessedAt = :at WHERE id = :id")
    suspend fun reinforce(id: Int, strength: Float, at: Long)

    /** The dreamer's re-tiering after it recomputes strength on the forgetting curve. */
    @Query("UPDATE memory_entry SET strength = :strength, tier = :tier WHERE id = :id")
    suspend fun retier(id: Int, strength: Float, tier: String)

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
