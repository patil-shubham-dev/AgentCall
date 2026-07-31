# AgentCall — Call Flow Architecture Report

## 1. Full Call Lifecycle

### Phase 1: Call Creation (MCP → Backend)

1. **MCP tool `create_call`** (`mcp-server/src/tools.ts:18-55`) calls `client.createCall()` (`mcp-server/src/client.ts:44-56`)
2. HTTP **`POST /api/v1/calls`** to backend (`backend/src/routes.ts:160-190`)
3. **`VoiceBridgeService.createCall()`** (`backend/src/voicebridge/service.ts:59-93`):
   - Creates `VoiceCallSession` with `status: 'pending'`, UUID `id`, empty `messages` array (one system message)
   - Stores in `SessionRepository`
   - Calls `notifyPhone(userId, { type: 'call_incoming', callId, reason, summary, options, priority })`
4. **`notifyPhone()`** (`backend/src/voicebridge/service.ts:229-251`):
   - Looks up `phoneConnections` map for the user's WebSocket
   - Sends `{"type": "call_incoming", "payload": {callId, reason, summary, ...}, "timestamp": "..."}` over the WS

### Phase 2: Phone Rings

5. **`SignalingClient`** (`mobile/android/.../SignalingClient.kt`) receives the message:
   - `onMessage()` at line 152 → `handleMessage()` at line 201
   - Parses `type = "call_incoming"` → emits `VoiceBridgeEvent.CallIncoming(callId, reason, summary, callerName)` onto `_events` SharedFlow (line 220)
6. **`HomeViewModel.init`** collects events (`mobile/android/.../HomeViewModel.kt` lines 58-143):
   - Receives `CallIncoming` → ensures AI profile exists in Room DB → sends `IncomingCallEvent` via Channel (line 85-90)
7. **`HomeScreen`** collects `incomingCallEvents` (`mobile/android/.../HomeScreen.kt` lines 62-75):
   - Calls `CallService.showIncomingCallNotification()` — posts high-priority `CATEGORY_CALL` notification with `FullScreenIntent` pointing to `IncomingCallActivity`
   - Starts `IncomingCallActivity` via `startActivity()` with `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TOP`
8. **`IncomingCallActivity.onCreate()`** (`mobile/android/.../IncomingCallActivity.kt`):
   - Reads `call_id`, `caller_name`, `context_summary` from intent extras
   - Starts ringtone via `startRinger()`
   - Displays `IncomingCallScreen` composable (animated rings, caller name, summary, 60s countdown, Answer/Decline/Later buttons)
   - Starts collecting `CallEventBus.events` → on `CallEvent.CallEnded`: `delay(500); finish()`

### Phase 3: User Answers

9. **`onAnswer()`** in `IncomingCallActivity` (`IncomingCallActivity.kt` lines 120-129):
   - `stopRinger()` / `cancelIncomingNotification()`
   - `startService(Intent(ACTION_START_CALL))` → **`CallService`**
   - `showCall = true` → Compose recomposes to show `ActiveCallScreen` in-place
10. **`CallService.onStartCommand(ACTION_START_CALL)`** (`CallService.kt` lines 89-99):
    - Sets `callId` from intent extra
    - `startForeground()` with "Ongoing call" notification
    - Acquires wake lock
    - `scope.launch { repository.markCallAnswered() }` — saves to local Room DB only (NO backend notification)
    - Calls `startVoiceSession(callId)`
11. **`startVoiceSession()`** (`CallService.kt` lines 163-206):
    - Fetches call data from backend API via `api.getCall(callId)` (still `status: "pending"` — no "active" transition happens here)
    - Speaks call summary via Android TTS (`speakTextOnMain`)
    - **Starts collecting `SignalingClient.events`** — enters an infinite `collect` loop
12. **`ActiveCallScreen`** renders inside `IncomingCallActivity`:
    - `CallViewModel` created via Hilt (`hiltViewModel()`), scoped to `IncomingCallActivity`
    - `viewModel.connect(callId)` fetches call data from API + collects `CallEventBus.events`
    - UI shows transcript, waveform, controls

### Phase 4: AI Message Arrives

13. **MCP tool `send_message`** (`mcp-server/src/tools.ts:57-83`) calls `client.sendMessage()` → `POST /api/v1/calls/{callId}/messages` with `{ content }`
14. Backend **`VoiceBridgeService.addAiMessage()`** (`backend/src/voicebridge/service.ts:113-123`):
    - Calls `addMessage()` with `role: 'ai'`
    - **Transitions `session.status` from `'pending'` to `'active'`** (line 101-103)
    - Sends `{"type": "ai_message", "payload": {callId, message: {id, role, content, createdAt}}}` via WebSocket
15. **`SignalingClient.handleMessage()`** parses `"ai_message"` → emits `VoiceBridgeEvent.AiMessage(callId, messageId, content)` (line 228)
16. **`CallService`** receives `AiMessage` → `speakTextOnMain(event.content)`, saves to DB, emits `CallEvent.AiMessage` (lines 179-184)
17. **`CallViewModel`** collects `CallEvent.AiMessage` → `addAiMessage()` adds to UI transcript (line 83)

### Phase 5: User Responds

18. User taps Record button → `CallService.ACTION_START_RECORDING` → `startRecording()` (line 208)
19. **`SpeechRecognizer`** runs → `onResults` callback (lines 223-228):
    - Gets recognized text
    - Synchronously calls `api.sendUserText(callId, mapOf("text" to text))` — **`POST /api/v1/calls/{callId}/user-text`**
    - Emits `CallEvent.UserMessage(text)` → `CallViewModel.addUserTranscript()`
20. Backend **`processTextMessage()`** (`backend/src/voicebridge/service.ts:129-138`):
    - Calls `addMessage(callId, 'user', text, 'text')` — appends to session messages
    - **No notification sent to AI side** — no WebSocket message, no HTTP callback, no SSE event. The text is simply appended to the session in-memory.

### Phase 6: Call Ends

21. **From phone**: User taps End → `ActiveCallScreen.onEndCall` → `CallService.ACTION_END_CALL`:
    - `CallEventBus.emit(CallEvent.CallEnded)`
    - `endCall()` (releases wake lock, stops TTS, disconnects signaling, removes notification)
    - `stopSelf()`
22. **From server**: Backend receives `POST /api/v1/calls/{callId}/complete` or `cancel`:
    - Sets `session.status = 'completed'` or `'cancelled'`
    - Sends `{"type": "call_ended"}` or `{"type": "call_cancelled"}` via WebSocket
    - `CallService` receives → emits `CallEventBus.CallEnded`, speaks "Call ended.", saves to DB, `delay(1500); stopSelf()`
23. **`IncomingCallActivity`** receives `CallEvent.CallEnded` → `delay(500); finish()`

---

## 2. WebSocket/Signaling Protocol — Message Types

Defined in `backend/src/voicebridge/service.ts` (outgoing to phone) and `mobile/android/.../SignalingClient.kt:208-253` (incoming parsing).

| Direction | Type | Payload | When |
|-----------|------|---------|------|
| Server → Phone | `connected` | `{ user_id, server }` | On WS connect |
| Server → Phone | `call_incoming` | `{ callId, reason, summary, callerName?, options?, priority?, isCallback? }` | On `create_call` or callback resume |
| Server → Phone | `ai_message` | `{ callId, message: { id, role, content, createdAt } }` | On `send_message` API call |
| Server → Phone | `callback_scheduled` | `{ callId, delayMinutes, resumeAt }` | On schedule callback |
| Server → Phone | `call_ended` | `{ callId }` | On `complete_call` API call |
| Server → Phone | `call_cancelled` | `{ callId }` | On `cancel_call` API call |
| Server → Phone | `call_expired` | `{ callId, reason }` | On pause TTL expiry (24h) |
| Server → Phone | `error` | `{ code, message }` | On rate limit, validation, auth error |
| Phone → Server | *(none sent by `SignalingClient`)* | | The phone never sends messages over the signaling WebSocket |

**Critical finding: the phone never sends any messages over the signaling WebSocket.** There is no `call_answered` message, no `user_text` message over WS. All phone→server communication goes through HTTP REST API calls (`POST /calls/{callId}/user-text`, etc.). The WebSocket is strictly server→phone push only.

---

## 3. Does the Current Architecture Support Continuous Back-and-Forth?

### **No.**

The MCP tools are fundamentally **one-shot request/response**:

| Tool | Behavior |
|------|----------|
| `create_call` | Single POST → returns `call_id` |
| `send_message` | Single POST → returns `message_id` |
| `get_transcript` | Single GET → returns messages array |
| `complete_call` | Single POST → ends call |
| `cancel_call` | Single POST → cancels call |

Each tool handler (`mcp-server/src/tools.ts`):
1. Receives arguments
2. Makes **one** HTTP request via `fetch`
3. Returns the result immediately

**No polling, no streaming, no WebSocket, no SSE, no long-running session, no event subscription.**

### The gap:

When the human replies (via voice or text):
1. `CallService` POSTs to `POST /calls/{callId}/user-text` (`CallService.kt:229`)
2. Backend appends the text to the session's `messages` array (`service.ts:129-138`)
3. **Nothing notifies the AI.** No WebSocket message, no HTTP callback, no SSE event, no MCP notification.
4. The AI must manually call `get_transcript` to discover the reply.

There is **no mechanism** for:
- The backend to push an event to the MCP server
- The MCP server to forward a notification to the AI (Claude, ChatGPT, etc.)
- The AI to maintain a long-lived "session" that receives replies in real-time

The `SignalingClient` SharedFlow on the phone is equally one-directional — it receives messages from the server but never sends user replies back over the WebSocket.

---

## 4. Auto-Disconnect-on-Answer — Root Cause Analysis

### What I ruled out:

| Hypothesis | Verdict |
|-----------|---------|
| Backend sends `call_ended` immediately | **FALSE** — No answer timeout exists anywhere in the backend. `pending` calls sit forever. |
| WebSocket `Disconnected` event kills call | **FALSE** — `VoiceBridgeEvent.Disconnected` is defined in the sealed class (`SignalingClient.kt:31`) but **never emitted** by `SignalingClient`. `onFailure` (line 157) and `onClosed` (line 173) only handle reconnection internally without emitting to `_events`. The handler in `CallService:197-200` is dead code. |
| `CallEventBus.Replay` replays old `CallEnded` | **FALSE** — `MutableSharedFlow<CallEvent>()` has `replay=0` (default). |
| Backend sweeper cleans up pending calls | **FALSE** — `isExpired()` (service.ts:256-259) requires `retentionExpiresAt` to be set, which only happens on `completed`/`cancelled`. |

### What remains: the most likely root cause

**The `VoiceBridgeEvent.CallEnded` and `VoiceBridgeEvent.CallCancelled` handlers in `CallService.startVoiceSession()` (`CallService.kt:185-196`) do not validate `event.callId` against the current active `callId`.**

Relevant code:
```kotlin
// CallService.kt:185-196
is VoiceBridgeEvent.CallEnded -> {
    CallEventBus.emit(CallEvent.CallEnded)          // <-- no callId check
    speakTextOnMain("Call ended.")
    repository.saveCallEnded(callId, "ended")       // uses current callId
    delay(1500); stopSelf()
}
is VoiceBridgeEvent.CallCancelled -> {
    CallEventBus.emit(CallEvent.CallEnded)          // <-- no callId check
    speakTextOnMain("Call was cancelled.")
    repository.saveCallEnded(callId, "cancelled")   // uses current callId
    delay(1500); stopSelf()
}
```

When a `call_ended` or `call_cancelled` event arrives for **any** call (not just the current active one), it immediately:
1. Emits `CallEventBus.CallEnded` → `IncomingCallActivity` calls `finish()` (line 161)
2. Speaks "Call ended" via TTS
3. Saves "ended" to local DB
4. Calls `stopSelf()` — kills the service

**Scenario that triggers this with the test flow:**
1. Test creates `Call A` via MCP → `Call A` is `pending`
2. MCP might cancel or complete `Call A` (or a previous test call) → backend sends `call_ended(A)`
3. If `Call B` is created shortly after, and `call_ended(A)` arrives during step 11-12 of the lifecycle (when `CallService` is collecting events for `Call B`), the `call_ended(A)` event kills `Call B`.

This is especially likely when multiple test calls are created in succession, and earlier ones are completed/cancelled by the test script while the user is answering a newer one.

Additionally, the `CallService.endCall()` (`CallService.kt:290-295`) calls `signalingClient.disconnect()`, which unregisters the network callback and closes the shared WebSocket — breaking signaling for the entire app, not just the current call.

---

## 5. What Would Need to Change (High-Level Shape)

### Fix 1: Validate `event.callId` in CallService handlers
```kotlin
is VoiceBridgeEvent.CallEnded -> {
    if (event.callId != callId) return@collect  // ignore events for other calls
    ...
}
```
Same for `CallCancelled`. This is the immediate fix for the auto-disconnect.

### Fix 2: Remove or fix the `Disconnected` handler
Either remove the dead code, or actually emit `Disconnected` from `SignalingClient` and change behavior to show "Reconnecting..." UI instead of killing the call.

### Fix 3: Tell backend when call is answered
The phone should send a WebSocket message or HTTP call when the user answers, so the backend knows the call is active.

### Fix 4: Backend → AI push mechanism
For continuous conversation support, the architecture needs **one of**:
- **MCP tool becomes long-lived**: Instead of one-shot tools, the AI maintains a "call session" tool that streams events back (like SSE). The AI calls `observe_call(call_id)` which opens a streaming response that delivers user replies in real-time.
- **WebSocket on the MCP server**: The MCP server maintains its own WebSocket connection to the backend and forwards `user_reply` events to the AI as new tool invocations.
- **Backend polls/callbacks**: The backend calls an MCP `user_replied` tool or sends an HTTP callback when the user responds.

**The first option (streaming MCP tool) is the most idiomatic for the MCP protocol.** The AI would:
1. `create_call` — starts the call (as now)
2. `send_message` — speaks to the user (as now)
3. `observe_call` — **new streaming tool** that blocks and returns user replies as they arrive
4. Loop: send_message → observe_call → process reply → send_message → ...

This matches how real conversation works — the AI "listens" between speaking, rather than polling a transcript endpoint.
