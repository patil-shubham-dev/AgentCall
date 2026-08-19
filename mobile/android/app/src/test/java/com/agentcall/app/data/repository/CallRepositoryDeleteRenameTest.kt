package com.agentcall.app.data.repository

import com.agentcall.app.data.api.AiKeyCreateRequest
import com.agentcall.app.data.api.AiKeyCreateResponse
import com.agentcall.app.data.api.AiKeyDeleteResponse
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.api.AiKeyListResponse
import com.agentcall.app.data.api.AiKeyRenameRequest
import com.agentcall.app.data.api.AiKeyRenameResponse
import com.agentcall.app.data.api.AgentStatusResponse
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.CallbackRequest
import com.agentcall.app.data.api.CallbackResponse
import com.agentcall.app.data.api.CancelRequest
import com.agentcall.app.data.api.CompleteRequest
import com.agentcall.app.data.api.StatusResponse
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import com.agentcall.app.data.model.ActiveCallResponse
import com.agentcall.app.data.model.CallResponse
import com.agentcall.app.data.model.FcmTokenRequest
import com.agentcall.app.data.model.FcmTokenResponse
import com.agentcall.app.data.model.PhoneRegisterResponse
import com.agentcall.app.data.model.SendMessageResponse
import com.agentcall.app.data.model.TranscriptResponse
import com.agentcall.app.data.model.UserTextResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Self-test for the delete/rename keyId fix (2026-08-19). Exercises the real
 * CallRepository against stateful fakes: the fake "server" actually holds
 * keys and mutates them, so every assertion about revocation/rename is a
 * follow-up through the same API surface the app calls — and a shared event
 * log proves the order (server first, local only after).
 */
class CallRepositoryDeleteRenameTest {

    private lateinit var log: MutableList<String>
    private lateinit var profileDao: FakeAiProfileDao
    private lateinit var callDao: FakeCallRecordDao
    private lateinit var transcriptDao: FakeTranscriptMessageDao
    private lateinit var api: FakeApiService
    private lateinit var repo: CallRepository

    @Before
    fun setUp() {
        log = mutableListOf()
        callDao = FakeCallRecordDao(log)
        transcriptDao = FakeTranscriptMessageDao(log, callDao)
        profileDao = FakeAiProfileDao(log)
        api = FakeApiService(log)
        repo = CallRepository(profileDao, callDao, transcriptDao, api)
        // ensurePhoneToken() early-returns when a token is preset — no network.
        ApiClient.phoneToken = "test-token"
    }

    // ── 1. Delete: bound key that exists ──────────────────────────────────

    @Test
    fun `delete with bound key revokes server key first then removes local rows`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1", callCount = 3)
        callDao.calls["c1"] = CallRecordEntity(
            callId = "c1", agentId = "alpha", callerName = "Alpha", status = "completed",
            reason = "", summary = "", startedAt = 1L,
        )
        transcriptDao.messages += TranscriptMessageEntity(callId = "c1", role = "ai", content = "hi", createdAt = "t")

        val outcome = repo.deleteAgent(profileDao.profiles["alpha"]!!)

        assertEquals(DeleteOutcome.RevokedAndDeleted, outcome)
        // Follow-up through the same API surface: the key is actually gone.
        assertTrue(api.keys.isEmpty())
        assertTrue(api.listAiKeys().keys.isEmpty())
        // Local rows gone.
        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
        assertTrue(transcriptDao.messages.isEmpty())
        // Order proven by the shared log: revoke ran before any local delete.
        val revokeIdx = log.indexOf("api.deleteKey:k1")
        val profileDelIdx = log.indexOf("profile.delete:alpha")
        assertTrue("revoke($revokeIdx) must precede local delete($profileDelIdx)", revokeIdx >= 0 && revokeIdx < profileDelIdx)
    }

    // ── 2. Delete: orphan (zero server matches) ───────────────────────────

    @Test
    fun `orphan with zero server matches returns NoServerKey and revokes nothing`() = runBlocking {
        api.keys["other"] = FakeKey("other", "Someone Else")
        profileDao.profiles["ghost"] = AiProfileEntity(id = "ghost", name = "Ghost", keyId = null)

        val outcome = repo.deleteAgent(profileDao.profiles["ghost"]!!)

        assertEquals(DeleteOutcome.NoServerKey, outcome)
        assertFalse(log.any { it.startsWith("api.deleteKey") })
        assertTrue(log.contains("api.listKeys")) // the lookup that confirmed zero matches
        // "Keep it": nothing was touched.
        assertEquals(1, profileDao.profiles.size)
        assertEquals("Ghost", profileDao.profiles["ghost"]!!.name)
        assertEquals(1, api.keys.size)
    }

    @Test
    fun `bound key that 404s on revoke also maps to NoServerKey`() = runBlocking {
        profileDao.profiles["a"] = AiProfileEntity(id = "a", name = "A", keyId = "gone-id")
        val outcome = repo.deleteAgent(profileDao.profiles["a"]!!)
        assertEquals(DeleteOutcome.NoServerKey, outcome)
        assertTrue(log.contains("api.deleteKey:gone-id"))
        assertEquals(1, profileDao.profiles.size) // nothing deleted locally
    }

    @Test
    fun `local-only removal after NoServerKey makes zero server calls`() = runBlocking {
        api.keys["other"] = FakeKey("other", "Someone Else")
        profileDao.profiles["ghost"] = AiProfileEntity(id = "ghost", name = "Ghost", keyId = null)
        callDao.calls["c1"] = CallRecordEntity(
            callId = "c1", agentId = "ghost", callerName = "Ghost", status = "missed",
            reason = "", summary = "", startedAt = 1L,
        )

        repo.deleteAgentLocalOnly(profileDao.profiles["ghost"]!!)

        // Zero server calls attempted — not even a list.
        assertTrue(log.none { it.startsWith("api.") })
        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
    }

    // ── 3. Delete: ambiguous name ─────────────────────────────────────────

    @Test
    fun `ambiguous name hard-errors with Settings hint and deletes nothing`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Twin")
        api.keys["k2"] = FakeKey("k2", "Twin")
        profileDao.profiles["twin"] = AiProfileEntity(id = "twin", name = "Twin", keyId = null)

        val outcome = repo.deleteAgent(profileDao.profiles["twin"]!!)

        assertTrue(outcome is DeleteOutcome.Failed)
        val message = (outcome as DeleteOutcome.Failed).message
        assertTrue("message was: $message", message.contains("Delete it from Settings"))
        assertTrue(message.contains("Nothing was deleted"))
        assertFalse(log.any { it.startsWith("api.deleteKey") }) // never guessed
        assertEquals(2, api.keys.size) // neither key deleted
        assertEquals(1, profileDao.profiles.size) // local untouched
    }

    // ── 4. Delete: agent with an active call ──────────────────────────────

    @Test
    fun `delete is blocked while the agent has an active call - no history loss`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "MidCall")
        api.activeCallByAgent["MidCall"] = "live"
        profileDao.profiles["mid"] = AiProfileEntity(id = "mid", name = "MidCall", keyId = "k1")
        callDao.calls["live"] = CallRecordEntity(
            callId = "live", agentId = "mid", callerName = "MidCall", status = "active",
            reason = "", summary = "", startedAt = 1L,
        )

        val outcome = repo.deleteAgent(profileDao.profiles["mid"]!!)

        // Blocked with the exact user-facing message — no revoke, no delete.
        assertTrue(outcome is DeleteOutcome.Failed)
        val message = (outcome as DeleteOutcome.Failed).message
        assertTrue("message was: $message", message.contains("active call"))
        assertTrue("message was: $message", message.contains("Try again once the call ends"))
        assertFalse(log.any { it.startsWith("api.deleteKey") }) // never revoked
        assertTrue(api.keys.containsKey("k1")) // server key untouched
        assertTrue(profileDao.profiles.containsKey("mid")) // profile untouched
        assertTrue(callDao.calls.containsKey("live")) // HISTORY INTACT — the whole point

        // When the call ends normally, the row still exists to be updated.
        repo.saveCallEnded("live", "completed")
        assertEquals("completed", repo.getCallRecord("live")!!.status)
    }

    @Test
    fun `delete fails closed when the call status cannot be checked`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        api.failStatusCheck = true
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")

        val outcome = repo.deleteAgent(profileDao.profiles["alpha"]!!)

        // Never delete into the unknown: the status check is the guard, and
        // when it fails the delete is refused with an explicit message.
        assertTrue(outcome is DeleteOutcome.Failed)
        assertTrue((outcome as DeleteOutcome.Failed).message.contains("Couldn't check"))
        assertFalse(log.any { it.startsWith("api.deleteKey") })
        assertTrue(api.keys.containsKey("k1"))
        assertTrue(profileDao.profiles.containsKey("alpha"))
    }

    // ── 5. Rename: bound agent ────────────────────────────────────────────

    @Test
    fun `rename propagates server and local, next ring lands in the same profile`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")

        repo.renameProfile("alpha", "Beta")

        assertEquals("Beta", api.keys["k1"]!!.name) // server took the new name
        assertEquals("Beta", profileDao.profiles["alpha"]!!.name) // local matches
        assertEquals("k1", profileDao.profiles["alpha"]!!.keyId) // binding survives
        assertTrue(log.contains("api.renameKey:k1:Beta"))

        // Ring from the renamed agent: slug lookup hits the same profile.
        val ring = repo.ensureProfileExists("beta", "Beta")
        assertEquals("alpha", ring)
        assertEquals(1, profileDao.profiles.size) // no duplicate
    }

    @Test
    fun `server-side rename while local stale self-heals on next ring via keyId`() = runBlocking {
        // Crash window: PATCH applied server-side, phone died before local rename.
        api.keys["k1"] = FakeKey("k1", "Beta")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")

        val ring = repo.ensureProfileExists("beta", "Beta")

        assertEquals("alpha", ring)
        assertEquals("Beta", profileDao.profiles["alpha"]!!.name) // healed
        assertEquals(1, profileDao.profiles.size) // no duplicate
    }

    // ── 6. Rename: orphan ─────────────────────────────────────────────────

    @Test
    fun `rename orphan hard-errors with no-escape message and changes nothing`() = runBlocking {
        api.keys["other"] = FakeKey("other", "Someone Else")
        profileDao.profiles["ghost"] = AiProfileEntity(id = "ghost", name = "Ghost", keyId = null)

        var caught: Exception? = null
        try {
            repo.renameProfile("ghost", "NewName")
        } catch (e: Exception) {
            caught = e
        }

        assertNotNull(caught)
        assertTrue("was ${caught!!.message}", caught is AgentSyncException)
        assertTrue((caught as AgentSyncException).message!!.contains("no matching key"))
        assertFalse(log.any { it.startsWith("api.renameKey") }) // no PATCH attempted
        assertEquals("Ghost", profileDao.profiles["ghost"]!!.name) // local untouched
    }

    // ── 7. Rename: collision with an existing key ─────────────────────────

    @Test
    fun `rename onto an existing key's name is rejected - server 409, local untouched`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        api.keys["k2"] = FakeKey("k2", "Beta")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")

        var caught: Exception? = null
        try {
            repo.renameProfile("alpha", "Beta")
        } catch (e: Exception) {
            caught = e
        }

        // The server (mirrored by the fake) rejects the collision with a 409,
        // and the repository surfaces it as a clear instruction.
        assertNotNull(caught)
        assertTrue(caught is AgentSyncException)
        assertTrue("message was: ${caught!!.message}", caught.message!!.contains("already named"))

        // Follow-up through the same API surface: no duplicate was created.
        assertEquals("Alpha", api.keys["k1"]!!.name) // server rejected, unchanged
        assertEquals("Alpha", profileDao.profiles["alpha"]!!.name) // local untouched
        assertEquals(1, api.keys.values.count { it.name == "Beta" }) // still exactly one Beta

        // Because no ambiguity exists, an unbound Beta profile now deletes
        // cleanly — the collision bug this whole fix closes.
        profileDao.profiles["beta-other"] = AiProfileEntity(id = "beta-other", name = "Beta", keyId = null)
        val outcome = repo.deleteAgent(profileDao.profiles["beta-other"]!!)
        assertEquals(DeleteOutcome.RevokedAndDeleted, outcome)
        assertTrue(api.keys.containsKey("k1")) // only k2 was revoked
    }

    // ── 9. Network drop mid-operation ─────────────────────────────────────

    @Test
    fun `revoke succeeds but local delete fails - profile survives and retry recovers via escape hatch`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")
        callDao.failNextDelete = true

        var caught: Exception? = null
        try {
            repo.deleteAgent(profileDao.profiles["alpha"]!!)
        } catch (e: Exception) {
            caught = e
        }

        assertNotNull("local failure must surface, not be swallowed", caught)
        assertTrue(api.keys.isEmpty()) // server key WAS revoked
        assertEquals(1, profileDao.profiles.size) // local side survived → inconsistent

        // Retry: the bound keyId now 404s → NoServerKey → local-only cleans up.
        val retry = repo.deleteAgent(profileDao.profiles["alpha"]!!)
        assertEquals(DeleteOutcome.NoServerKey, retry)
        repo.deleteAgentLocalOnly(profileDao.profiles["alpha"]!!)
        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
    }

    @Test
    fun `rename server succeeds but local rename fails - next ring self-heals`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")
        profileDao.failNextRename = true

        var caught: Exception? = null
        try {
            repo.renameProfile("alpha", "Beta")
        } catch (e: Exception) {
            caught = e
        }

        assertNotNull("local failure must surface", caught)
        assertEquals("Beta", api.keys["k1"]!!.name) // server renamed
        assertEquals("Alpha", profileDao.profiles["alpha"]!!.name) // local stale

        // Next ring carries the server-side name → keyId match heals the profile.
        val ring = repo.ensureProfileExists("beta", "Beta")
        assertEquals("alpha", ring)
        assertEquals("Beta", profileDao.profiles["alpha"]!!.name)
        assertEquals(1, profileDao.profiles.size)
    }

    // ── Fakes ─────────────────────────────────────────────────────────────

    private class FakeKey(val id: String, var name: String)

    private class FakeAiProfileDao(private val log: MutableList<String>) : AiProfileDao {
        val profiles = mutableMapOf<String, AiProfileEntity>()
        var failNextRename = false

        override fun getAllProfiles(): Flow<List<AiProfileEntity>> =
            MutableStateFlow(profiles.values.sortedByDescending { it.updatedAt })
        override suspend fun getProfile(id: String): AiProfileEntity? = profiles[id]
        override suspend fun getProfileByKeyId(keyId: String): AiProfileEntity? =
            profiles.values.find { it.keyId == keyId }
        override suspend fun getProfiles(): List<AiProfileEntity> = profiles.values.toList()
        override suspend fun upsert(profile: AiProfileEntity) { profiles[profile.id] = profile }
        override suspend fun renameProfile(id: String, name: String, updatedAt: Long) {
            if (failNextRename) {
                failNextRename = false
                throw java.io.IOException("simulated local rename failure")
            }
            log += "profile.rename:$id:$name"
            profiles[id]?.let { profiles[id] = it.copy(name = name, updatedAt = updatedAt) }
        }
        override suspend fun updateKeyId(id: String, keyId: String) {
            log += "profile.bindKey:$id:$keyId"
            profiles[id]?.let { profiles[id] = it.copy(keyId = keyId) }
        }
        override suspend fun delete(profile: AiProfileEntity) {
            log += "profile.delete:${profile.id}"
            profiles.remove(profile.id)
        }
        override suspend fun incrementCallCount(id: String, lastCalledAt: Long) {
            profiles[id]?.let { profiles[id] = it.copy(callCount = it.callCount + 1, lastCalledAt = lastCalledAt) }
        }
        override suspend fun deleteById(id: String) {
            log += "profile.delete:$id"
            profiles.remove(id)
        }
    }

    private class FakeCallRecordDao(private val log: MutableList<String>) : CallRecordDao {
        val calls = mutableMapOf<String, CallRecordEntity>()
        var failNextDelete = false

        override fun getAllCalls(): Flow<List<CallRecordEntity>> = MutableStateFlow(calls.values.toList())
        override fun getCallsForProfile(agentId: String): Flow<List<CallRecordEntity>> =
            MutableStateFlow(calls.values.filter { it.agentId == agentId })
        override suspend fun getCall(callId: String): CallRecordEntity? = calls[callId]
        override suspend fun upsert(call: CallRecordEntity) { calls[call.callId] = call }
        override suspend fun endCall(callId: String, status: String, endedAt: Long, durationSeconds: Int) {
            log += "call.end:$callId"
            calls[callId]?.let { calls[callId] = it.copy(status = status, endedAt = endedAt, durationSeconds = durationSeconds) }
        }
        override suspend fun updateSummary(callId: String, summary: String) {
            calls[callId]?.let { calls[callId] = it.copy(summary = summary) }
        }
        override suspend fun markTranscriptFetched(callId: String) {
            calls[callId]?.let { calls[callId] = it.copy(transcriptFetched = true) }
        }
        override suspend fun deleteForAgent(agentId: String) {
            if (failNextDelete) {
                failNextDelete = false
                throw java.io.IOException("simulated local delete failure")
            }
            log += "call.delete:$agentId"
            calls.entries.removeAll { it.value.agentId == agentId }
        }
    }

    private class FakeTranscriptMessageDao(
        private val log: MutableList<String>,
        private val callDao: FakeCallRecordDao,
    ) : TranscriptMessageDao {
        val messages = mutableListOf<TranscriptMessageEntity>()

        override fun getMessagesForCall(callId: String): Flow<List<TranscriptMessageEntity>> =
            MutableStateFlow(messages.filter { it.callId == callId })
        override suspend fun insertAll(messages: List<TranscriptMessageEntity>) { this.messages += messages }
        override suspend fun deleteForCall(callId: String) { messages.removeAll { it.callId == callId } }
        override suspend fun deleteForAgent(agentId: String) {
            log += "transcript.delete:$agentId"
            messages.removeAll { msg -> callDao.calls[msg.callId]?.agentId == agentId }
        }
    }

    private class FakeApiService(private val log: MutableList<String>) : ApiService {
        val keys = mutableMapOf<String, FakeKey>()
        /** agent display name → live call id, mirroring GET /agents/:id/status. */
        val activeCallByAgent = mutableMapOf<String, String>()
        var failStatusCheck = false

        override suspend fun listAiKeys(): AiKeyListResponse {
            log += "api.listKeys"
            return AiKeyListResponse(keys.values.map { AiKeyItem(keyId = it.id, name = it.name) })
        }
        override suspend fun deleteAiKey(keyId: String): AiKeyDeleteResponse {
            log += "api.deleteKey:$keyId"
            if (keys.remove(keyId) == null) throw httpError(404)
            return AiKeyDeleteResponse(status = "deleted", keyId = keyId)
        }
        override suspend fun renameAiKey(keyId: String, body: AiKeyRenameRequest): AiKeyRenameResponse {
            log += "api.renameKey:$keyId:${body.name}"
            val key = keys[keyId] ?: throw httpError(404)
            // Mirror the server's unique-name rule: renaming onto another
            // key's name → 409 (self-rename stays a no-op).
            val takenBy = keys.entries.firstOrNull { it.value.name == body.name && it.key != keyId }
            if (takenBy != null) throw httpError(409)
            key.name = body.name
            return AiKeyRenameResponse(keyId = keyId, name = body.name)
        }
        override suspend fun createAiKey(body: AiKeyCreateRequest): AiKeyCreateResponse {
            log += "api.createKey:${body.name}"
            // Mirror the server's unique-name rule on POST.
            if (keys.values.any { it.name == body.name }) throw httpError(409)
            val id = "k${keys.size + 1}"
            keys[id] = FakeKey(id, body.name)
            return AiKeyCreateResponse(keyId = id, name = body.name, key = "ac_test")
        }
        override suspend fun getAgentStatus(agentId: String): AgentStatusResponse {
            log += "api.status:$agentId"
            if (failStatusCheck) throw java.io.IOException("simulated status-check failure")
            return AgentStatusResponse(
                agentId = agentId,
                online = false,
                currentCallId = activeCallByAgent[agentId],
            )
        }
        override suspend fun getTranscript(callId: String): TranscriptResponse {
            log += "api.getTranscript:$callId"
            return TranscriptResponse(callId = callId, messages = emptyList())
        }
        override suspend fun getCall(callId: String): CallResponse = unused()
        override suspend fun sendMessage(callId: String, body: Map<String, String>): SendMessageResponse = unused()
        override suspend fun completeCall(callId: String, body: CompleteRequest): StatusResponse = unused()
        override suspend fun cancelCall(callId: String, body: CancelRequest): StatusResponse = unused()
        override suspend fun answerCall(callId: String): StatusResponse = unused()
        override suspend fun scheduleCallback(callId: String, body: CallbackRequest): CallbackResponse = unused()
        override suspend fun getActiveCall(userId: String): ActiveCallResponse = unused()
        override suspend fun registerPhone(): PhoneRegisterResponse = unused()
        override suspend fun registerFcmToken(body: FcmTokenRequest): FcmTokenResponse = unused()
        override suspend fun sendUserText(callId: String, body: Map<String, String>): UserTextResponse = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("not used in this test")

        private fun httpError(code: Int): retrofit2.HttpException {
            val response = retrofit2.Response.error<Any>(code, "{}".toResponseBody(null))
            return retrofit2.HttpException(response)
        }
    }
}