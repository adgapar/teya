package com.teya.agent.household

import android.content.Context
import com.teya.agent.safety.TeyaDatabase
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
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
        // Everything not in the always-loaded persona block: the general pool + any cooled (COLD)
        // persona memories that dropped out of context. This is what recall reaches for.
        val pool = dao.searchable()
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

        // Recall reinforces: reset to full strength + stamp access time ("use it or lose it"). The
        // next dream run re-promotes a reinforced COLD memory back to HOT.
        val now = System.currentTimeMillis()
        ranked.forEach { dao.reinforce(it.id, 1.0f, now) }
        return ranked
    }

    private fun keywordMatch(pool: List<MemoryEntry>, q: String, topK: Int): List<MemoryEntry> =
        pool.filter { it.text.lowercase().contains(q) }.take(topK)

    /** Everything Teya has stored, newest first — for the Admin review screen. */
    suspend fun all(): List<MemoryEntry> = dao.getAll()

    /** Delete one memory by id — the Admin review screen's manual "forget". */
    suspend fun delete(id: Int) = dao.delete(id)

    /**
     * The "dreamer"'s deterministic half: recompute every memory's strength on the forgetting curve
     * (per-category half-life since [MemoryEntry.lastAccessedAt]), re-tier HOT↔COLD, and prune dead
     * EPISODIC rows. Pure math, no LLM — safe to run nightly (HarnessService's dream alarm) or on
     * demand from Admin. Returns a summary for the monitor/log. (LLM consolidation of episodic detail
     * into durable facts is a later slice.)
     */
    suspend fun runDecay(now: Long = System.currentTimeMillis()): DreamSummary {
        val all = dao.getAll()
        var cooled = 0
        var pruned = 0
        all.forEach { e ->
            val s = strengthNow(e, now)
            if (e.category == CAT_EPISODIC && s < DEAD_THRESHOLD) {
                dao.delete(e.id)
                pruned++
            } else {
                val tier = if (s >= HOT_THRESHOLD) TIER_HOT else TIER_COLD
                dao.retier(e.id, s, tier)
                if (tier == TIER_COLD && e.tier == TIER_HOT) cooled++
            }
        }
        return DreamSummary(scanned = all.size, cooled = cooled, pruned = pruned, at = now)
    }

    /** Retention on the forgetting curve: 1.0 at last access, halving every [halfLifeDays] since. */
    private fun strengthNow(e: MemoryEntry, now: Long): Float {
        val elapsedDays = (now - e.lastAccessedAt).coerceAtLeast(0L) / 86_400_000.0
        return 0.5.pow(elapsedDays / halfLifeDays(e.category)).toFloat().coerceIn(0f, 1f)
    }

    /** Per-category half-life in days — FACT ~ permanent, EPISODIC fades fast (see the plan's table). */
    private fun halfLifeDays(category: String): Double = when (category.uppercase()) {
        CAT_EPISODIC -> 3.0
        CAT_PREFERENCE -> 45.0
        CAT_ROUTINE -> 120.0
        else -> 3650.0   // FACT (and anything unknown): effectively permanent
    }

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

        /** Minimum cosine similarity for a semantic search hit to count (filters out junk matches). */
        private const val MIN_SIM = 0.35f
        /** Strength at/above which a memory stays HOT (always-loaded); below → COLD (search-only). */
        private const val HOT_THRESHOLD = 0.5f
        /** Below this, a decayed EPISODIC memory is pruned for good (durable categories only cool). */
        private const val DEAD_THRESHOLD = 0.05f
    }
}

/** What a dream run did — surfaced in the Admin monitor + logs. */
data class DreamSummary(val scanned: Int, val cooled: Int, val pruned: Int, val at: Long) {
    fun note(): String = "scanned $scanned · cooled $cooled · pruned $pruned"
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
