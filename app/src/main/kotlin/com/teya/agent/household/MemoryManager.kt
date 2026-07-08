package com.teya.agent.household

import android.content.Context
import com.teya.agent.safety.TeyaDatabase

/**
 * Teya's durable memory: append-only [MemoryEntry] rows retrieved two ways —
 * - **persona memory** by *association* (`WHERE subjectKey = member`), assembled into context every
 *   turn ("what you remember about Dad"), and
 * - the **general pool** by *similarity* (RAG, fetched on demand — later slice).
 *
 * A "block" is a **render-time assembly**, not a stored self-editing object — mirrors
 * [HouseholdManager.profileContextBlock]. [category] says what a memory *is* (and later drives its
 * decay half-life + mutability); [subjectType]/[subjectKey] say who it's *about*. The nightly
 * "dreamer" (later slice) recomputes [MemoryEntry.strength] and re-tiers HOT↔COLD; for now every
 * memory is HOT (always loaded). See thoughts/shared/plans/2026-07-08-memory-and-dreaming.md.
 */
class MemoryManager(context: Context) {
    private val dao = TeyaDatabase.get(context).memoryDao()

    /**
     * Append a memory. [subjectKey] is a member's Contacts lookupKey (with [subjectType] = CONTACT)
     * or null for a family-wide fact. Returns the new row id, or -1 if the text was blank.
     */
    suspend fun remember(
        text: String,
        subjectType: String = SUBJECT_GENERAL,
        subjectKey: String? = null,
        category: String? = null,
    ): Long {
        val clean = text.trim()
        if (clean.isEmpty()) return -1L
        val now = System.currentTimeMillis()
        return dao.insert(
            MemoryEntry(
                subjectType = subjectType,
                subjectKey = subjectKey,
                text = clean,
                addedAt = now,
                category = normalizeCategory(category),
                strength = 1.0f,
                lastAccessedAt = now,
                embedding = null,
                tier = TIER_HOT,
            )
        )
    }

    /**
     * Forget memories whose text loosely matches [query] — the destructive inverse of [remember],
     * and the only way to truly delete. Scoped to one subject when [subjectKey] is given. Returns
     * how many rows were removed.
     */
    suspend fun forget(query: String, subjectKey: String? = null): Int {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return 0
        val candidates = if (subjectKey != null) dao.bySubject(subjectKey) else dao.getAll()
        val hits = candidates.filter {
            val t = it.text.lowercase()
            t.contains(q) || q.contains(t)
        }
        hits.forEach { dao.delete(it.id) }
        return hits.size
    }

    /** Everything Teya has stored, newest first — for the Admin review screen. */
    suspend fun all(): List<MemoryEntry> = dao.getAll()

    /**
     * The "What you remember" block folded into the live context every turn: the HOT memories,
     * grouped per household member (persona memory) with anything else under "General". Empty when
     * there's nothing to say. [members] is passed in (already loaded by the caller) so we don't
     * re-read Contacts.
     */
    suspend fun memoryContextBlock(members: List<Member>): String {
        val hot = dao.hot()
        if (hot.isEmpty()) return ""

        val nameByKey = members.mapNotNull { m -> m.lookupKey?.let { it to m.displayName } }.toMap()
        val byMember = LinkedHashMap<String, MutableList<String>>()   // displayName -> facts
        val general = mutableListOf<String>()
        hot.forEach { e ->
            val name = if (e.subjectType == SUBJECT_CONTACT) nameByKey[e.subjectKey] else null
            if (name != null) byMember.getOrPut(name) { mutableListOf() }.add(e.text)
            else general.add(e.text)
        }

        val sb = StringBuilder("What you remember (authoritative — durable memory about this family):\n")
        byMember.forEach { (name, facts) -> sb.append("- $name: ").append(facts.joinToString("; ")).append("\n") }
        if (general.isNotEmpty()) sb.append("- General: ").append(general.joinToString("; ")).append("\n")
        return sb.toString().trimEnd()
    }

    private fun normalizeCategory(raw: String?): String {
        val c = raw?.trim()?.uppercase()
        return if (c != null && c in CATEGORIES) c else CAT_FACT
    }

    companion object {
        const val SUBJECT_CONTACT = "CONTACT"
        const val SUBJECT_PERSONA = "PERSONA"
        const val SUBJECT_GENERAL = "GENERAL"

        const val CAT_FACT = "FACT"
        const val CAT_PREFERENCE = "PREFERENCE"
        const val CAT_ROUTINE = "ROUTINE"
        const val CAT_EPISODIC = "EPISODIC"
        private val CATEGORIES = setOf(CAT_FACT, CAT_PREFERENCE, CAT_ROUTINE, CAT_EPISODIC)

        const val TIER_HOT = "HOT"
        const val TIER_COLD = "COLD"
    }
}
