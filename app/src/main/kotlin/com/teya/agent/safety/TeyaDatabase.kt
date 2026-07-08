package com.teya.agent.safety

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.teya.agent.household.ContactExtra
import com.teya.agent.household.ContactExtraDao
import com.teya.agent.household.MemoryDao
import com.teya.agent.household.MemoryEntry
import com.teya.agent.household.Persona
import com.teya.agent.household.PersonaDao

@Database(
    entities = [Contact::class, Persona::class, MemoryEntry::class, ContactExtra::class],
    version = 2,
)
abstract class TeyaDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun personaDao(): PersonaDao
    abstract fun memoryDao(): MemoryDao
    abstract fun contactExtraDao(): ContactExtraDao

    companion object {
        @Volatile
        private var instance: TeyaDatabase? = null

        /**
         * v1→v2: add the three Teya-brain tables. CREATE TABLE IF NOT EXISTS only — the existing
         * `contact_allowlist` table is never touched, so the call allowlist survives the upgrade.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `persona` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `aliases` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_entry` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`subjectType` TEXT NOT NULL, `subjectKey` TEXT, " +
                        "`text` TEXT NOT NULL, `addedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `contact_extra` " +
                        "(`lookupKey` TEXT NOT NULL, `aliases` TEXT NOT NULL, " +
                        "PRIMARY KEY(`lookupKey`))"
                )
            }
        }

        /** Single shared instance so every manager opens the same migrated DB (name: "teya-db"). */
        fun get(context: Context): TeyaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, TeyaDatabase::class.java, "teya-db"
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
