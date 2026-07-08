package com.teya.agent.household

import android.content.Context
import com.teya.agent.safety.TeyaDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

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
        embedding: FloatArray? = null,
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
                embedding = embedding?.toBytes(),
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

    /**
     * Semantic search over the general pool (RAG) for [query]. With a [queryEmbedding], ranks embedded
     * rows by cosine (keeping only those above [MIN_SIM]); with none, or if no row is embedded, falls
     * back to a keyword contains-match. Reinforces the hits ("use it or lose it") and returns up to
     * [topK] rows, strongest first. Persona memory isn't searched here — it's always in context.
     */
    suspend fun search(query: String, queryEmbedding: FloatArray?, topK: Int = 5): List<MemoryEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val pool = dao.general()
        if (pool.isEmpty()) return emptyList()

        val ranked = if (queryEmbedding != null) {
            val scored = pool.mapNotNull { e ->
                val emb = e.embedding?.toFloatArray() ?: return@mapNotNull null
                e to cosine(queryEmbedding, emb)
            }
            if (scored.isEmpty()) keywordMatch(pool, q, topK)
            else scored.filter { it.second >= MIN_SIM }.sortedByDescending { it.second }.take(topK).map { it.first }
        } else {
            keywordMatch(pool, q, topK)
        }

        val now = System.currentTimeMillis()
        ranked.forEach { dao.reinforce(it.id, (it.strength + REINFORCE_BOOST).coerceAtMost(1f), now) }
        return ranked
    }

    private fun keywordMatch(pool: List<MemoryEntry>, q: String, topK: Int): List<MemoryEntry> =
        pool.filter { it.text.lowercase().contains(q) }.take(topK)

    /** Everything Teya has stored, newest first — for the Admin review screen. */
    suspend fun all(): List<MemoryEntry> = dao.getAll()

    /** Delete one memory by id — the Admin review screen's manual "forget". */
    suspend fun delete(id: Int) = dao.delete(id)

    /**
     * The "What you remember" block folded into the live context every turn: the HOT memories,
     * grouped per household member (persona memory) with anything else under "General". Empty when
     * there's nothing to say. [members] is passed in (already loaded by the caller) so we don't
     * re-read Contacts.
     */
    suspend fun memoryContextBlock(members: List<Member>): String {
        // Only PERSONA memory (about a member) is always-loaded. General-pool memories are
        // retrieved on demand via search() — keeping this block bounded no matter how much the
        // family accumulates over a year.
        val persona = dao.hot().filter { it.subjectType == SUBJECT_CONTACT }
        if (persona.isEmpty()) return ""

        val nameByKey = members.mapNotNull { m -> m.lookupKey?.let { it to m.displayName } }.toMap()
        val byMember = LinkedHashMap<String, MutableList<String>>()   // displayName -> facts
        persona.forEach { e ->
            val name = nameByKey[e.subjectKey] ?: return@forEach   // member deleted → skip
            byMember.getOrPut(name) { mutableListOf() }.add(e.text)
        }
        if (byMember.isEmpty()) return ""

        val sb = StringBuilder("What you remember (authoritative — durable memory about this family):\n")
        byMember.forEach { (name, facts) -> sb.append("- $name: ").append(facts.joinToString("; ")).append("\n") }
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

        /** How much a search hit bumps strength (reinforcement); capped at 1.0. */
        private const val REINFORCE_BOOST = 0.25f
        /** Minimum cosine similarity for a semantic search hit to count (filters out junk matches). */
        private const val MIN_SIM = 0.35f
    }
}

/** float32-LE round-trip for storing an embedding vector in a Room BLOB column. */
private fun FloatArray.toBytes(): ByteArray {
    val buf = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buf.putFloat(it) }
    return buf.array()
}

private fun ByteArray.toFloatArray(): FloatArray {
    val buf = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(size / 4) { buf.float }
}

/** Cosine similarity in [-1, 1]; 0 for a size mismatch or a zero vector. */
private fun cosine(a: FloatArray, b: FloatArray): Float {
    if (a.size != b.size) return 0f
    var dot = 0f; var na = 0f; var nb = 0f
    for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
    val denom = sqrt(na) * sqrt(nb)
    return if (denom == 0f) 0f else dot / denom
}
