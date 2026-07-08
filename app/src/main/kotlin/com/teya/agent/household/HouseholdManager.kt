package com.teya.agent.household

import android.content.Context
import com.teya.agent.harness.ConfigManager
import com.teya.agent.safety.TeyaDatabase

/**
 * The household profile: who the family is (native Contacts + Room alias augmentation) and what
 * they speak (ConfigManager). Exposes accessors for the UI (Admin/onboarding) and, crucially,
 * [profileContextBlock] — the system-prompt block that makes Teya contextual and keeps her from
 * replying in a language TTS can't voice.
 */
class HouseholdManager(context: Context) {
    private val contacts = ContactsRepository(context)
    private val contactExtraDao = TeyaDatabase.get(context).contactExtraDao()
    private val config = ConfigManager(context)

    /** The roster: contacts marked as members (they have a [ContactExtra] row), merged with aliases. */
    suspend fun members(): List<Member> = contactExtraDao.getAll().mapNotNull { extra ->
        val f = contacts.read(extra.lookupKey) ?: return@mapNotNull null
        Member(
            first = f.first, last = f.last, email = f.email, phone = f.phone,
            birthday = f.birthday,
            aliases = extra.aliases.toAliasList(),
            lookupKey = extra.lookupKey,
        )
    }

    fun languages(): List<String> = config.languages

    /**
     * Replace the whole household: delete previously-seeded members, insert the given ones fresh,
     * and rewrite the alias augmentation. Idempotent — both onboarding and Admin "Save changes"
     * call it. Members without a name are dropped (a name is the minimum identity).
     */
    suspend fun saveHousehold(members: List<Member>) {
        contactExtraDao.getAll().forEach { contacts.delete(it.lookupKey) }
        contactExtraDao.clear()

        val clean = members.filter { it.hasName }
        val lookupKeys = contacts.insertMembers(clean)
        clean.forEachIndexed { i, m ->
            val key = lookupKeys.getOrNull(i) ?: return@forEachIndexed
            contactExtraDao.upsert(
                ContactExtra(lookupKey = key, aliases = m.aliases.filter { it.isNotBlank() }.joinToString(","))
            )
        }
    }

    fun saveLanguages(langs: List<String>) { config.languages = langs }

    /**
     * System-prompt block, rebuilt every turn (edits in Admin apply with no restart). States the
     * roster + the reply-language directive derived from (household ∩ TTS-9). Empty when nothing is
     * configured yet, so a fresh install adds nothing to the prompt.
     */
    suspend fun profileContextBlock(): String {
        val members = members()
        val langs = languages()
        if (members.isEmpty() && langs.isEmpty()) return ""

        val sb = StringBuilder("Household profile (authoritative — the family Teya serves):\n")

        if (members.isNotEmpty()) {
            sb.append("- Members:\n")
            members.forEach { m ->
                val called = m.aliases.filter { it.isNotBlank() }
                val calledStr = if (called.isNotEmpty()) " — called ${called.joinToString(", ")}" else ""
                val bdayStr = if (m.birthday.isNotBlank()) " (birthday ${m.birthday})" else ""
                sb.append("    • ${m.displayName}$calledStr$bdayStr\n")
            }
            sharedAliasNote(members)?.let { sb.append("- $it\n") }
        }

        sb.append("- Languages: ").append(languageDirective(langs))
        return sb.toString().trimEnd()
    }

    /**
     * The reply-language directive — built entirely from config (the household languages), with no
     * language names hardcoded in the prompt. Dominant rule: match the language of the person's most
     * recent message, not the device location or the household's other languages. She only ever
     * replies in a language she can actually voice (household ∩ TTS-capable, plus English as the
     * universal fallback); a message in anything else falls back to English. That speakable-set
     * constraint is what prevents the generate-untts-able-text bug — no need to name languages.
     */
    private fun languageDirective(langs: List<String>): String {
        val household = langs.ifEmpty { listOf("English") }
        val speakable = (household + "English").distinct().filter { Languages.isVoiced(it) }
        val understandOnly = household.filter { !Languages.isVoiced(it) }

        val understandClause = if (understandOnly.isNotEmpty())
            " You understand ${understandOnly.joinNatural()} but cannot speak it aloud." else ""
        return "The household speaks ${household.joinNatural()}. " +
            "Reply in the SAME language as the person's most recent message — detect it from their " +
            "words alone, ignoring the device location and the household's other languages. " +
            "You can speak these aloud: ${speakable.joinNatural()}.$understandClause " +
            "Only ever reply in a language you can speak aloud; if a message is in any other language, " +
            "reply in English instead."
    }

    /** If two members share an alias (case-insensitive), tell Teya to ask which one is meant. */
    private fun sharedAliasNote(members: List<Member>): String? {
        val byAlias = mutableMapOf<String, MutableList<String>>()
        members.forEach { m ->
            m.aliases.filter { it.isNotBlank() }.forEach { a ->
                byAlias.getOrPut(a.lowercase()) { mutableListOf() }.add(m.displayName)
            }
        }
        val shared = byAlias.values.filter { it.size > 1 }
        if (shared.isEmpty()) return null
        val examples = shared.joinToString("; ") { it.joinToString(" or ") }
        return "Some names are shared ($examples). When one is used, ask which person is meant."
    }
}

/** "A" · "A and B" · "A, B and C" — reads naturally in the prompt. */
private fun List<String>.joinNatural(): String = when (size) {
    0 -> ""
    1 -> this[0]
    2 -> "${this[0]} and ${this[1]}"
    else -> "${dropLast(1).joinToString(", ")} and ${last()}"
}

internal fun String.toAliasList(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }
