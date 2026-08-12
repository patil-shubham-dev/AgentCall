package com.agentcall.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcall.app.data.database.entity.CallRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {
    @Query("SELECT * FROM call_records ORDER BY startedAt DESC")
    fun getAllCalls(): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE agentId = :agentId ORDER BY startedAt DESC")
    fun getCallsForProfile(agentId: String): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE callId = :callId")
    suspend fun getCall(callId: String): CallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(call: CallRecordEntity)

    @Query("UPDATE call_records SET status = :status, endedAt = :endedAt, durationSeconds = :durationSeconds WHERE callId = :callId")
    suspend fun endCall(callId: String, status: String, endedAt: Long, durationSeconds: Int)

    @Query("UPDATE call_records SET summary = :summary WHERE callId = :callId")
    suspend fun updateSummary(callId: String, summary: String)

    @Query("UPDATE call_records SET transcriptFetched = 1 WHERE callId = :callId")
    suspend fun markTranscriptFetched(callId: String)
}
