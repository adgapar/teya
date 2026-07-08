package com.teya.agent.household

/**
 * A household member. Identity fields (first/last/email/phone) live in native Contacts; [aliases]
 * (what the family calls them — "Dad", "Papa", "Babcia") live in Room [ContactExtra]. [lookupKey]
 * is the Contacts row key; null for a member still being entered in the UI (not yet persisted).
 */
data class Member(
    val first: String = "",
    val last: String = "",
    val aliases: List<String> = emptyList(),
    val email: String = "",
    val phone: String = "",
    val birthday: String = "",   // ISO "YYYY-MM-DD"; stored in Contacts as a birthday Event
    val lookupKey: String? = null,
) {
    val displayName: String
        get() = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Someone" }

    val hasName: Boolean get() = first.isNotBlank() || last.isNotBlank()
}
