package com.agentcall.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity

@Database(
    entities = [AiProfileEntity::class, CallRecordEntity::class, TranscriptMessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AgentCallDatabase : RoomDatabase() {
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun callRecordDao(): CallRecordDao
    abstract fun transcriptMessageDao(): TranscriptMessageDao
}
