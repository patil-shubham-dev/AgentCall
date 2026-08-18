package com.agentcall.app.data.repository

import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import com.agentcall.app.call.agentSlug
import com.agentcall.app.data.model.ActiveCall
import com.agentcall.app.data.model.CallResponse
import com.agentcall.app.data.model.TranscriptMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

    /**
     * Backlog item 1: create the history row the moment a ring starts, so a
     * decline note, an expiry, or an answer all have a record to update.
     * Without this, unanswered rings would never appear in history at all
     * (saveCallEnded no-ops on a missing row) and the transcript FK would
     * reject the decline/voicemail note insert.
     */
    suspend fun markCallRinging(callId: String, agentId: String, callerName: String, startedAt: Long) {
        callDao.upsert(
            CallRecordEntity(
                callId = callId,
                agentId = agentId,
                callerName = callerName,
                status = "ringing",
                reason = "",
                summary = "",
                startedAt = startedAt,
            )
        )
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

    /** Backlog item 3: the AI-generated recap, written once at call end. */
    suspend fun saveCallSummary(callId: String, summary: String) {
        if (summary.isBlank()) return
        callDao.updateSummary(callId, summary.trim())
    }

    /** Raw record lookup (missed-call notification needs agentId + name). */
    suspend fun getCallRecord(callId: String): CallRecordEntity? = callDao.getCall(callId)

    suspend fun getProfile(id: String): AiProfileEntity? = profileDao.getProfile(id)

    /**
     * Resolve a profile by its display name: profiles are keyed by the
     * slugified name, but some call sites only have the display name.
     */
    suspend fun getProfileByName(name: String): AiProfileEntity? {
        profileDao.getProfile(name)?.let { return it }
        return profileDao.getProfile(name.agentSlug())
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

    suspend fun checkActiveCall(userId: String): ActiveCall? {
        return try {
            val response = withContext(Dispatchers.IO) { api.getActiveCall(userId) }
            response.activeCall
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCallStatus(callId: String): String? {
        return try {
            withContext(Dispatchers.IO) { api.getCall(callId) }.status
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getCallDetails(callId: String): CallResponse? {
        return try {
            withContext(Dispatchers.IO) { api.getCall(callId) }
        } catch (_: Exception) {
            null
        }
    }

    // Phase A (FCM push-to-wake): register this device's Firebase token so the
    // backend can push rings via FCM alongside the WS/poll path. Requires the
    // phone token for auth; safe to call on every token refresh (idempotent).
    suspend fun registerFcmToken(fcmToken: String) {
        ApiClient.ensurePhoneToken()
        withContext(Dispatchers.IO) {
            api.registerFcmToken(com.agentcall.app.data.model.FcmTokenRequest(fcmToken))
        }
    }

    suspend fun fetchTranscriptRemote(callId: String): List<TranscriptMessage> {
        return try {
            withContext(Dispatchers.IO) { api.getTranscript(callId) }.messages
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ── Per-agent ringtone + quick replies (backlog items 7 & 9) ──────────

    suspend fun setProfileRingtone(agentId: String, uri: String?, label: String?) {
        profileDao.updateRingtone(agentId, uri, label)
    }

    suspend fun setProfileQuickReplies(agentId: String, chips: List<String>) {
        val clean = chips.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(MAX_QUICK_REPLIES)
        profileDao.updateQuickReplies(agentId, if (clean.isEmpty()) null else json.encodeToString(ListSerializer(String.serializer()), clean))
    }

    companion object {
        const val MAX_QUICK_REPLIES = 4
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse stored JSON chips; bad data degrades to an empty list. */
        fun parseQuickReplies(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                json.decodeFromString(ListSerializer(String.serializer()), raw)
            }.getOrDefault(emptyList())
        }
    }
}