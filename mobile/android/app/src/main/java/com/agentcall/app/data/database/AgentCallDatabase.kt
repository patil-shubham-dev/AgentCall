package com.agentcall.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity

@Database(
    entities = [AiProfileEntity::class, CallRecordEntity::class, TranscriptMessageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AgentCallDatabase : RoomDatabase() {
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun callRecordDao(): CallRecordDao
    abstract fun transcriptMessageDao(): TranscriptMessageDao

    companion object {
        /**
         * Migration 1 -> 2 (backlog items 7 & 9): per-agent ringtone +
         * per-agent quick-reply chips on the ai_profiles row.
         *
         * Additive columns only — no data rewrite, safe on every install. This
         * must never be replaced by destructive fallback outside DEBUG builds.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN ringtoneUri TEXT")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN ringtoneLabel TEXT")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN quickReplies TEXT")
            }
        }

        /**
         * Migration 2 -> 3: bind each profile to its server-side AI key id so
         * delete/rename/ring-matching stop depending on the mutable display
         * name.
         *
         * The ringtone/quickReplies columns are re-asserted here too so the
         * final schema matches the entity on EVERY upgrade path: v1->v3 (1_2
         * adds them, 2_3 adds keyId), v2-with-columns (2_3 adds keyId only),
         * and fresh-v2 devices whose table never had them (2_3 adds all
         * four). Adds are guarded by PRAGMA table_info because "duplicate
         * column" and "no such column" would each crash a different path.
         * Dropping them would require unsafe DROP COLUMN on minSdk 26; the
         * entity maps them as unused instead.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "ai_profiles", "keyId", "ALTER TABLE ai_profiles ADD COLUMN keyId TEXT")
                addColumnIfMissing(db, "ai_profiles", "ringtoneUri", "ALTER TABLE ai_profiles ADD COLUMN ringtoneUri TEXT")
                addColumnIfMissing(db, "ai_profiles", "ringtoneLabel", "ALTER TABLE ai_profiles ADD COLUMN ringtoneLabel TEXT")
                addColumnIfMissing(db, "ai_profiles", "quickReplies", "ALTER TABLE ai_profiles ADD COLUMN quickReplies TEXT")
            }
        }

        /** SQLite has no ADD COLUMN IF NOT EXISTS before 3.35 — check PRAGMA instead. */
        private fun addColumnIfMissing(db: SupportSQLiteDatabase, table: String, column: String, ddl: String) {
            val exists = db.query("PRAGMA table_info('$table')").use { cursor ->
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == column) {
                        found = true
                        break
                    }
                }
                found
            }
            if (!exists) db.execSQL(ddl)
        }
    }
}
