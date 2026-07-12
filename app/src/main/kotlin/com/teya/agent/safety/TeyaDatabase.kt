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
import com.teya.agent.household.VoiceSample
import com.teya.agent.household.VoiceSampleDao

@Database(
    entities = [Contact::class, Persona::class, MemoryEntry::class, ContactExtra::class, VoiceSample::class],
    version = 4,
)
abstract class TeyaDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun personaDao(): PersonaDao
    abstract fun memoryDao(): MemoryDao
    abstract fun contactExtraDao(): ContactExtraDao
    abstract fun voiceSampleDao(): VoiceSampleDao

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

        /**
         * v2→v3: `memory_entry` gains the memory decay/persona fields (category, strength,
         * lastAccessedAt, embedding, tier). The v2 table was seeded but **never populated** (no code
         * wrote to it), so recreating it is non-destructive — and it avoids `ALTER … ADD COLUMN NOT
         * NULL DEFAULT`, whose default must exactly match Room's schema check. New columns carry no
         * DB default (matching the entity, which has none); MemoryManager sets them on every insert.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `memory_entry`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_entry` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`subjectType` TEXT NOT NULL, `subjectKey` TEXT, " +
                        "`text` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, " +
                        "`category` TEXT NOT NULL, `strength` REAL NOT NULL, " +
                        "`lastAccessedAt` INTEGER NOT NULL, `embedding` BLOB, " +
                        "`tier` TEXT NOT NULL)"
                )
            }
        }

        /** v3→v4: add `voice_sample` (per-speaker voice ID enrollment — see `docs/roadmap.md`). */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_sample` " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`lookupKey` TEXT NOT NULL, `embedding` BLOB NOT NULL, " +
                        "`recordedAt` INTEGER NOT NULL)"
                )
            }
        }

        /** Single shared instance so every manager opens the same migrated DB (name: "teya-db"). */
        fun get(context: Context): TeyaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, TeyaDatabase::class.java, "teya-db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
        }
    }
}
