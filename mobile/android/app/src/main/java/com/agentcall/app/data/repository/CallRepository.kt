package com.agentcall.app.data.repository

import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import com.agentcall.app.data.model.TranscriptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepository @Inject constructor(
    private val profileDao: AiProfileDao,
    private val callDao: CallRecordDao,
    private val transcriptDao: TranscriptMessageDao,
    private val api: ApiService,
) {
    fun getAllProfiles(): Flow<List<AiProfileEntity>> = profileDao.getAllProfiles()

    suspend fun getOrCreateProfile(agentId: String, name: String): AiProfileEntity {
        val existing = profileDao.getProfile(agentId)
        if (existing != null) return existing
        val profile = AiProfileEntity(id = agentId, name = name)
        profileDao.upsert(profile)
        return profile
    }

    suspend fun renameProfile(id: String, newName: String) {
        profileDao.renameProfile(id, newName)
    }

    fun getAllCalls(): Flow<List<CallRecordEntity>> = callDao.getAllCalls()

    fun getCallsForProfile(agentId: String): Flow<List<CallRecordEntity>> =
        callDao.getCallsForProfile(agentId)

    suspend fun ensureProfileExists(agentId: String, name: String) {
        getOrCreateProfile(agentId, name)
    }

    suspend fun markCallAnswered(callId: String, agentId: String, callerName: String) {
        val now = System.currentTimeMillis()
        callDao.upsert(
            CallRecordEntity(
                callId = callId,
                agentId = agentId,
                callerName = callerName,
                status = "started",
                reason = "",
                summary = "",
                startedAt = now,
            )
        )
        profileDao.incrementCallCount(agentId, now)
    }

    suspend fun saveCallEnded(callId: String, status: String) {
        val call = callDao.getCall(callId) ?: return
        val now = System.currentTimeMillis()
        val duration = ((now - call.startedAt) / 1000).toInt()
        callDao.endCall(callId, status, now, duration)
        saveTranscriptLocally(callId)
    }

    suspend fun saveUserTextMessage(callId: String, text: String) {
        val msg = TranscriptMessageEntity(
            callId = callId,
            role = "user",
            content = text,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()),
        )
        transcriptDao.insertAll(listOf(msg))
    }

    suspend fun saveAiMessage(callId: String, text: String) {
        val msg = TranscriptMessageEntity(
            callId = callId,
            role = "ai",
            content = text,
            createdAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(java.util.Date()),
        )
        transcriptDao.insertAll(listOf(msg))
    }

    private suspend fun saveTranscriptLocally(callId: String) {
        try {
            val response = withContext(Dispatchers.IO) { api.getTranscript(callId) }
            val messages = response.messages.mapIndexed { index, msg ->
                TranscriptMessageEntity(
                    callId = callId,
                    role = msg.role,
                    content = msg.content,
                    createdAt = msg.createdAt,
                )
            }
            transcriptDao.deleteForCall(callId)
            transcriptDao.insertAll(messages)
            callDao.markTranscriptFetched(callId)
        } catch (_: Exception) {
        }
    }

    fun getTranscriptForCall(callId: String): Flow<List<TranscriptMessageEntity>> =
        transcriptDao.getMessagesForCall(callId)

    suspend fun ensureTranscriptFetched(callId: String) {
        val call = callDao.getCall(callId) ?: return
        if (!call.transcriptFetched) saveTranscriptLocally(callId)
    }
}