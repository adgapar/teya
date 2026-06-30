package com.teya.agent.safety

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_allowlist")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val phoneNumber: String,
    val relation: String? = null
)
