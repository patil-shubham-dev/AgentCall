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

    @Query("SELECT * FROM ai_profiles WHERE keyId = :keyId")
    suspend fun getProfileByKeyId(keyId: String): AiProfileEntity?

    @Query("SELECT * FROM ai_profiles")
    suspend fun getProfiles(): List<AiProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AiProfileEntity)

    @Query("UPDATE ai_profiles SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameProfile(id: String, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE ai_profiles SET keyId = :keyId WHERE id = :id")
    suspend fun updateKeyId(id: String, keyId: String)

    @Delete
    suspend fun delete(profile: AiProfileEntity)

    @Query("UPDATE ai_profiles SET callCount = callCount + 1, lastCalledAt = :lastCalledAt WHERE id = :id")
    suspend fun incrementCallCount(id: String, lastCalledAt: Long)

    @Query("DELETE FROM ai_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}
