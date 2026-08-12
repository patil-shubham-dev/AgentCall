package com.agentcall.app.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_profiles")
data class AiProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val callCount: Int = 0,
    val lastCalledAt: Long? = null,
    // Migration 1 -> 2 (backlog items 7 & 9): per-agent ringtone and
    // quick-reply chips live on the profile row. quickReplies is a JSON
    // string array (max 4 chips); parse defensively — bad JSON must degrade
    // to no chips, never crash.
    val ringtoneUri: String? = null,
    val ringtoneLabel: String? = null,
    val quickReplies: String? = null,
)
