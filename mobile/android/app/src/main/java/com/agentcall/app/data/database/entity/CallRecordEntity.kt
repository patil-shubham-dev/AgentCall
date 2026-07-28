package com.agentcall.app.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_records",
    foreignKeys = [ForeignKey(
        entity = AiProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["agentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("agentId")],
)
data class CallRecordEntity(
    @PrimaryKey val callId: String,
    val agentId: String,
    val callerName: String,
    val status: String,
    val reason: String,
    val summary: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationSeconds: Int = 0,
    val transcriptFetched: Boolean = false,
)
