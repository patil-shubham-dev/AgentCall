package com.agentcall.app.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_messages",
    foreignKeys = [ForeignKey(
        entity = CallRecordEntity::class,
        parentColumns = ["callId"],
        childColumns = ["callId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("callId")],
)
data class TranscriptMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val callId: String,
    val role: String,
    val content: String,
    val createdAt: String,
)
