package com.teya.agent.safety

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Contact::class], version = 1)
abstract class TeyaDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
}
