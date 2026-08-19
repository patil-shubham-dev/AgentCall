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
    /**
     * Kept mapped for schema-stability only — the per-agent ringtone and
     * quick-reply features were removed from the UI, but MIGRATION_1_2 added
     * these columns and MIGRATION_2_3 re-adds them so every upgrade path
     * (v1→v3, v2-with-columns, fresh-v2) converges on the same v3 schema.
     * Nothing reads or writes them.
     */
    val ringtoneUri: String? = null,
    val ringtoneLabel: String? = null,
    val quickReplies: String? = null,
    /**
     * Stable server-side identifier of the AI key this profile is bound to.
     * Null for profiles created before the binding existed — reconciled
     * lazily (see CallRepository.reconcileProfileKeyIds).
     */
    val keyId: String? = null,
)
