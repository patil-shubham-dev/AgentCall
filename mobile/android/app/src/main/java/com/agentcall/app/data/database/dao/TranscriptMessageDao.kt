package com.agentcall.app.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptMessageDao {
    @Query("SELECT * FROM transcript_messages WHERE callId = :callId ORDER BY createdAt ASC")
    fun getMessagesForCall(callId: String): Flow<List<TranscriptMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<TranscriptMessageEntity>)

    @Query("DELETE FROM transcript_messages WHERE callId = :callId")
    suspend fun deleteForCall(callId: String)
}
