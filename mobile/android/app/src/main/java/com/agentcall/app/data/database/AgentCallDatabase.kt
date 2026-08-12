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
    version = 2,
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
    }
}
