package com.agentcall.app.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcall.app.data.database.entity.AiProfileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AiProfileDao {
    @Query("SELECT * FROM ai_profiles ORDER BY updatedAt DESC")
    fun getAllProfiles(): Flow<List<AiProfileEntity>>

    @Query("SELECT * FROM ai_profiles WHERE id = :id")
    suspend fun getProfile(id: String): AiProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AiProfileEntity)

    @Query("UPDATE ai_profiles SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameProfile(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(profile: AiProfileEntity)

    @Query("UPDATE ai_profiles SET callCount = callCount + 1, lastCalledAt = :lastCalledAt WHERE id = :id")
    suspend fun incrementCallCount(id: String, lastCalledAt: Long)

    @Query("UPDATE ai_profiles SET ringtoneUri = :uri, ringtoneLabel = :label, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateRingtone(id: String, uri: String?, label: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE ai_profiles SET quickReplies = :json, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateQuickReplies(id: String, json: String?, updatedAt: Long = System.currentTimeMillis())
}
