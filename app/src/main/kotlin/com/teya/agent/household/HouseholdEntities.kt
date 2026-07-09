package com.teya.agent.household

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Teya's private "brain" tables — the augmentation she keeps beyond native Contacts. Household
 * members themselves live in [android.provider.ContactsContract] (see [ContactsRepository]); these
 * tables hold what Android has no home for: extra aliases, people learned by voice, and memories.
 *
 * v1 actively uses [ContactExtra] (membership marker + full alias list for household contacts).
 * [Persona] and [MemoryEntry] are seeded by the schema now but populated by the memory feature.
 */

/** A person Teya learned about by voice ("remember Uncle Bob…"), not a household member. */
@Entity(tableName = "persona")
data class Persona(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val aliases: String = "",   // CSV of "listen-for" words
)

/**
 * A single remembered fact. [subjectType]/[subjectKey] say who it's *about* (a household contact by
 * lookupKey, a [Persona], or general); [category] says what *kind* it is and drives its decay +
 * mutability. [strength] is its place on the forgetting curve, [tier] where it lives (HOT = loaded
 * into context, COLD = archival/RAG-only) — both maintained by the nightly dreamer.
 */
@Entity(tableName = "memory_entry")
data class MemoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val subjectType: String = "GENERAL",   // CONTACT | PERSONA | GENERAL — who it's about
    val subjectKey: String? = null,        // contact lookupKey, persona id, or null (general)
    val text: String,
    val addedAt: Long,
    val category: String = "FACT",         // FACT | PREFERENCE | ROUTINE | EPISODIC
    val strength: Float = 1.0f,            // forgetting-curve strength (1.0 = fresh); access reinforces
    val lastAccessedAt: Long = 0L,         // reinforcement timestamp ("use it or lose it"); = addedAt on insert
    val embedding: ByteArray? = null,      // float32 blob for general-pool RAG (cosine in code); null for persona rows
    val tier: String = "HOT",              // HOT = assembled into context; COLD = archival/RAG-only
)

/**
 * Teya's augmentation for a household contact, keyed by the contact's stable [lookupKey]. Its
 * presence also *marks* a contact as a household member (Contacts has no such flag). [aliases] is
 * the full CSV list of what the family calls the person; the Contacts Nickname mirrors only the
 * first alias (single-valued, transparent in Google Contacts).
 */
@Entity(tableName = "contact_extra")
data class ContactExtra(
    @PrimaryKey val lookupKey: String,
    val aliases: String = "",   // CSV
)
