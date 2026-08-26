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
 * Delete/rename contract tests (2026-08-19 redesign). Delete is now
 * deliberately LOCAL-ONLY: it removes the profile + call history + transcripts
 * from the device and makes zero server calls — the MCP key on the AI/harness
 * side stays untouched, so the agent can still call again. Rename stays
 * server-first (it must keep the server join consistent). A shared event log
 * proves which surface each operation actually touched.
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

    // ── 1. Delete: local-only, zero server calls ──────────────────────────

    @Test
    fun `delete removes profile call history and transcripts with zero server calls`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1", callCount = 3)
        callDao.calls["c1"] = CallRecordEntity(
            callId = "c1", agentId = "alpha", callerName = "Alpha", status = "completed",
            reason = "", summary = "", startedAt = 1L,
        )
        transcriptDao.messages += TranscriptMessageEntity(callId = "c1", role = "ai", content = "hi", createdAt = "t")

        repo.deleteAgent(profileDao.profiles["alpha"]!!)

        // Local rows gone — cascade: transcripts, calls, profile.
        assertTrue(transcriptDao.messages.isEmpty())
        assertTrue(callDao.calls.isEmpty())
        assertTrue(profileDao.profiles.isEmpty())
        // Zero server calls attempted — not even a status check or key list.
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
        // The server key is untouched — the MCP key on the AI side still works.
        assertEquals(1, api.keys.size)
        assertEquals("Alpha", api.keys["k1"]!!.name)
    }

    @Test
    fun `delete keeps the server key usable - a later ring recreates the profile fresh`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Alpha")
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")
        callDao.calls["c1"] = CallRecordEntity(
            callId = "c1", agentId = "alpha", callerName = "Alpha", status = "completed",
            reason = "", summary = "", startedAt = 1L,
        )

        repo.deleteAgent(profileDao.profiles["alpha"]!!)
        assertTrue(profileDao.profiles.isEmpty())

        // The agent calls again (its key was never revoked) → fresh profile,
        // and history from the OLD profile is NOT resurrected.
        val ring = repo.ensureProfileExists("alpha", "Alpha")
        assertEquals("alpha", ring)
        assertEquals(1, profileDao.profiles.size)
        assertEquals(0, callDao.calls.size)
        assertEquals("k1", profileDao.profiles["alpha"]!!.keyId)
    }

    // ── 2. Delete: unbound / stale-key profiles ───────────────────────────

    @Test
    fun `delete works for an unbound profile - no key lookup is needed`() = runBlocking {
        api.keys["other"] = FakeKey("other", "Someone Else")
        profileDao.profiles["ghost"] = AiProfileEntity(id = "ghost", name = "Ghost", keyId = null)
        callDao.calls["c1"] = CallRecordEntity(
            callId = "c1", agentId = "ghost", callerName = "Ghost", status = "missed",
            reason = "", summary = "", startedAt = 1L,
        )

        repo.deleteAgent(profileDao.profiles["ghost"]!!)

        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
        assertEquals(1, api.keys.size) // other keys untouched
    }

    @Test
    fun `delete works even when the bound keyId is stale on the server`() = runBlocking {
        // keyId points at a key that was already deleted server-side — under
        // the old flow this orphan-blocked the delete; now it just works.
        profileDao.profiles["a"] = AiProfileEntity(id = "a", name = "A", keyId = "gone-id")
        repo.deleteAgent(profileDao.profiles["a"]!!)
        assertTrue(profileDao.profiles.isEmpty())
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
    }

    @Test
    fun `delete works even when duplicate server names exist - no ambiguity block`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "Twin")
        api.keys["k2"] = FakeKey("k2", "Twin")
        profileDao.profiles["twin"] = AiProfileEntity(id = "twin", name = "Twin", keyId = null)

        repo.deleteAgent(profileDao.profiles["twin"]!!)

        assertTrue(profileDao.profiles.isEmpty())
        assertEquals(2, api.keys.size) // neither server key touched
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
    }

    // ── 3. Delete: active call ────────────────────────────────────────────

    @Test
    fun `delete is allowed while the agent has an active call - local rows are removed`() = runBlocking {
        api.keys["k1"] = FakeKey("k1", "MidCall")
        profileDao.profiles["mid"] = AiProfileEntity(id = "mid", name = "MidCall", keyId = "k1")
        callDao.calls["live"] = CallRecordEntity(
            callId = "live", agentId = "mid", callerName = "MidCall", status = "active",
            reason = "", summary = "", startedAt = 1L,
        )

        // The call itself is server-side and unaffected; only this device's
        // history row is removed. No status round-trip, no block.
        repo.deleteAgent(profileDao.profiles["mid"]!!)

        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
        assertEquals(1, api.keys.size) // server key untouched
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
    }

    @Test
    fun `delete never depends on the server - works while the backend is unreachable`() = runBlocking {
        // No keys at all (server asleep/empty): the old fail-closed status
        // check would have refused this; local-only delete just works.
        profileDao.profiles["alpha"] = AiProfileEntity(id = "alpha", name = "Alpha", keyId = "k1")

        repo.deleteAgent(profileDao.profiles["alpha"]!!)

        assertTrue(profileDao.profiles.isEmpty())
        assertTrue("server was contacted: $log", log.none { it.startsWith("api.") })
    }

    // ── 4. Delete: local failure surfaces and retry recovers ──────────────

    @Test
    fun `local delete failure surfaces and a retry cleans up`() = runBlocking {
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
        assertTrue(profileDao.profiles.containsKey("alpha")) // profile survived
        assertTrue(log.contains("call.delete:alpha")) // the failing step was attempted
        assertEquals(1, api.keys.size) // server untouched, retry is safe

        // Retry — no server dependency, so the retry is just another local pass.
        repo.deleteAgent(profileDao.profiles["alpha"]!!)
        assertTrue(profileDao.profiles.isEmpty())
        assertTrue(callDao.calls.isEmpty())
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

        // A duplicate-name profile (a "Twin"-style leftover) still deletes
        // cleanly — the ambiguity block only ever applied to rename.
        profileDao.profiles["beta-other"] = AiProfileEntity(id = "beta-other", name = "Beta", keyId = null)
        repo.deleteAgent(profileDao.profiles["beta-other"]!!)
        assertTrue(profileDao.profiles["beta-other"] == null)
        assertEquals(2, api.keys.size) // no server key revoked
    }

    // ── 8. Rename: network drop mid-operation ─────────────────────────────

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
            log += "call.delete:$agentId"
            if (failNextDelete) {
                failNextDelete = false
                throw java.io.IOException("simulated local delete failure")
            }
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
        override suspend fun getAgentStatus(agentId: String): AgentStatusResponse =
            AgentStatusResponse(agentId = agentId, online = false, currentCallId = null)
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