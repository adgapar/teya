package com.teya.agent.safety

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Legacy, unread. This was the call allowlist; nothing ever wrote to it, which is why every
 * `place_call` returned NOT_ALLOWED. The allowlist is now the household roster
 * (`telephony.TelephonyActuator` + `household.HouseholdManager`). Kept declared only so the Room
 * schema stays as it is and needs no migration — see [TeyaDatabase].
 */
@Entity(tableName = "contact_allowlist")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val relation: String? = null
)
