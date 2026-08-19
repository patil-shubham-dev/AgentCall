package com.agentcall.app.data.repository

import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.AiKeyRenameRequest
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
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A cross-boundary operation (delete/rename/ring matching) that depends on the
 * server-side AI key failed. [message] is safe to show the user verbatim.
 */
class AgentSyncException(message: String) : Exception(message)

/**
 * Result of attempting to delete an agent. The server-side revoke runs first
 * and always wins: local data is only removed after it succeeded.
 */
sealed interface DeleteOutcome {
    /** Key revoked on the server, local history + profile removed. */
    data object RevokedAndDeleted : DeleteOutcome

    /**
     * The server confirms no key exists for this agent (zero name matches, or
     * a 404 revoking a bound key id) — there is nothing left to revoke, so
     * the caller may offer a local-only removal.
     */
    data object NoServerKey : DeleteOutcome

    /** Revoke failed (network, auth, ambiguity) — nothing was deleted. */
    data class Failed(val message: String) : DeleteOutcome
}

/** Result of resolving a key id by display name. */
private sealed interface KeyIdLookup {
    data class Exact(val keyId: String) : KeyIdLookup
    data object None : KeyIdLookup
    data object Ambiguous : KeyIdLookup
}

@Singleton
class CallRepository @Inject constructor(
    private val profileDao: AiProfileDao,
    private val callDao: CallRecordDao,
    private val transcriptDao: TranscriptMessageDao,
    private val api: ApiService,
) {
    fun getAllProfiles(): Flow<List<AiProfileEntity>> = profileDao.getAllProfiles()

    /**
     * Find the server-side key id for a display name. Throws on network
     * failure (the caller decides how to treat it); returns [KeyIdLookup.None]
     * or [KeyIdLookup.Ambiguous] for genuine results so callers never guess.
     */
    private suspend fun lookupKeyIdByName(name: String): KeyIdLookup {
        ApiClient.ensurePhoneToken()
        val matches = withContext(Dispatchers.IO) { api.listAiKeys() }.keys.filter { it.name == name }
        return when {
            matches.size == 1 -> KeyIdLookup.Exact(matches[0].keyId)
            matches.isEmpty() -> KeyIdLookup.None
            else -> KeyIdLookup.Ambiguous
        }
    }

    /**
     * Backfill keyId bindings for profiles created before the binding
     * existed. Unambiguous names only — a name matching several keys stays
     * unbound rather than guessing (see delete/rename hard-error paths).
     */
    suspend fun reconcileProfileKeyIds() {
        val unbound = profileDao.getProfiles().filter { it.keyId == null }
        if (unbound.isEmpty()) return
        val keys = runCatching {
            ApiClient.ensurePhoneToken()
            withContext(Dispatchers.IO) { api.listAiKeys() }.keys
        }.getOrNull() ?: return
        for (profile in unbound) {
            val matches = keys.filter { it.name == profile.name }
            if (matches.size == 1) profileDao.updateKeyId(profile.id, matches[0].keyId)
        }
    }

    suspend fun getOrCreateProfile(agentId: String, name: String, keyId: String? = null): AiProfileEntity {
        val existing = profileDao.getProfile(agentId)
        if (existing != null) {
            if (keyId != null && existing.keyId == null) {
                profileDao.updateKeyId(agentId, keyId)
            }
            return existing
        }
        val profile = AiProfileEntity(id = agentId, name = name, keyId = keyId)
        profileDao.upsert(profile)
        return profile
    }

    /**
     * Ensure the profile exists and return the canonical profile id the ring
     * should record history under. Handles the rename case: if the agent was
     * renamed server-side, the slug lookup misses, but the keyId lookup finds
     * the existing profile and updates its name instead of creating a
     * duplicate. Falls back to slug creation when the server can't be reached
     * or the name is ambiguous — a ring must never be blocked by this.
     */
    suspend fun ensureProfileExists(agentId: String, name: String): String {
        profileDao.getProfile(agentId)?.let { return it.id }
        val keyId = runCatching { lookupKeyIdByName(name) }.getOrNull()
        return when (keyId) {
            is KeyIdLookup.Exact -> {
                val byKey = profileDao.getProfileByKeyId(keyId.keyId)
                if (byKey != null) {
                    if (byKey.name != name) profileDao.renameProfile(byKey.id, name)
                    byKey.id
                } else {
                    getOrCreateProfile(agentId, name, keyId.keyId).id
                }
            }
            else -> getOrCreateProfile(agentId, name, null).id
        }
    }

    /**
     * Rename the agent everywhere: server first (so the join stays consistent),
     * then locally. Fails hard with a user-safe message if the key can't be
     * resolved or the server rename fails — the local name is never changed
     * behind the server's back (that was the duplicate-profile bug).
     */
    suspend fun renameProfile(id: String, newName: String) {
        val profile = profileDao.getProfile(id)
            ?: throw AgentSyncException("This agent no longer exists locally")
        val keyId = profile.keyId ?: when (val lookup = lookupKeyIdByName(profile.name)) {
            is KeyIdLookup.Exact -> lookup.keyId
            KeyIdLookup.None -> throw AgentSyncException(
                "Couldn't rename \"${profile.name}\": no matching key exists on the server " +
                    "(it may have been deleted). Nothing was renamed."
            )
            KeyIdLookup.Ambiguous -> throw AgentSyncException(
                "Couldn't rename \"${profile.name}\": multiple keys share that name. " +
                    "Rename it from Settings instead."
            )
        }
        try {
            withContext(Dispatchers.IO) {
                api.renameAiKey(keyId, AiKeyRenameRequest(newName))
            }
        } catch (e: HttpException) {
            if (e.code() == 409) {
                throw AgentSyncException(
                    "Another agent is already named \"$newName\". Pick a different name."
                )
            }
            throw AgentSyncException(
                "Couldn't rename \"${profile.name}\" on the server: ${e.message ?: "unknown error"}. Nothing was renamed."
            )
        } catch (e: Exception) {
            throw AgentSyncException(
                "Couldn't rename \"${profile.name}\" on the server: ${e.message ?: "unknown error"}. Nothing was renamed."
            )
        }
        if (profile.keyId == null) profileDao.updateKeyId(id, keyId)
        profileDao.renameProfile(id, newName)
    }

    fun getAllCalls(): Flow<List<CallRecordEntity>> = callDao.getAllCalls()

    fun getCallsForProfile(agentId: String): Flow<List<CallRecordEntity>> =
        callDao.getCallsForProfile(agentId)

    /**
     * Backlog item 1: create the history row the moment a ring starts, so a
     * decline note, an expiry, or an answer all have a record to update.
     * Without this, unanswered rings would never appear in history at all
     * (saveCallEnded no-ops on a missing row) and the transcript FK would
     * reject the decline note insert.
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

    /**
     * Permanently remove an agent: revoke its backend AI key, then delete its
     * local history (transcripts first, then calls, then the profile row).
     *
     * Revocation is keyed by the stable server-side key id and runs FIRST —
     * if the key can't be found or revoked, nothing is deleted locally. Delete
     * never pretends success when the server side didn't actually happen.
     *
     * Returns [DeleteOutcome.NoServerKey] when the server confirms no key
     * exists at all (zero name matches for an unbound profile, or a 404 while
     * revoking a bound key id) — the caller can then offer a local-only
     * removal, because there is nothing left to revoke.
     */
    suspend fun deleteAgent(profile: AiProfileEntity): DeleteOutcome {
        // Block before anything else: an agent with an open call must not be
        // deleted, or the live call's history row would be wiped and the call
        // would end unrecorded (saveCallEnded no-ops on a missing row). The
        // check is server-side (GET /agents/:id/status → current_call_id), so
        // it stays true regardless of whether the app is foregrounded. If the
        // status can't be determined we fail CLOSED — never delete into the
        // unknown, never a silent no-op.
        val status = try {
            ApiClient.ensurePhoneToken()
            withContext(Dispatchers.IO) { api.getAgentStatus(profile.name) }
        } catch (e: Exception) {
            return DeleteOutcome.Failed(
                "Couldn't check if \"${profile.name}\" is in a call — try again. Nothing was deleted."
            )
        }
        if (status.currentCallId != null) {
            return DeleteOutcome.Failed(
                "Can't delete — this agent has an active call. Try again once the call ends."
            )
        }

        val keyId = profile.keyId ?: when (val lookup = lookupKeyIdByName(profile.name)) {
            is KeyIdLookup.Exact -> lookup.keyId
            KeyIdLookup.None -> return DeleteOutcome.NoServerKey
            KeyIdLookup.Ambiguous -> return DeleteOutcome.Failed(
                "Couldn't revoke \"${profile.name}\": multiple keys share that name. " +
                    "Delete it from Settings instead. Nothing was deleted."
            )
        }
        try {
            withContext(Dispatchers.IO) { api.deleteAiKey(keyId) }
        } catch (e: HttpException) {
            if (e.code() == 404) return DeleteOutcome.NoServerKey
            return DeleteOutcome.Failed(
                "Couldn't revoke the key on the server: ${e.message ?: "unknown error"}. Nothing was deleted."
            )
        } catch (e: Exception) {
            return DeleteOutcome.Failed(
                "Couldn't revoke the key on the server: ${e.message ?: "unknown error"}. Nothing was deleted."
            )
        }
        if (profile.keyId == null) profileDao.updateKeyId(profile.id, keyId)
        deleteLocal(profile.id)
        return DeleteOutcome.RevokedAndDeleted
    }

    /**
     * Remove a profile and its history from this device without any server
     * call. Only reachable after the server confirmed the key no longer
     * exists ([DeleteOutcome.NoServerKey]) — for a still-live key this would
     * orphan the server-side credential.
     */
    suspend fun deleteAgentLocalOnly(profile: AiProfileEntity) {
        deleteLocal(profile.id)
    }

    private suspend fun deleteLocal(profileId: String) {
        transcriptDao.deleteForAgent(profileId)
        callDao.deleteForAgent(profileId)
        profileDao.deleteById(profileId)
    }
}