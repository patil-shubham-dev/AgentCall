# API & Protocol Specification — [DEPRECATED]

> **⚠️ DEPRECATED: This document describes the previous API contract and is retained for historical context only.**
>
> **Canonical source:** See [API_SPEC.md](../API_SPEC.md) for the current API contract.
> **System architecture:** See [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md).
> **Reason for deprecation:** The API was redesigned with 8 MCP tools (up from 5), JWT/OAuth authentication, and an expanded resource model including Providers, Presence, Notifications, and Devices. The WebRTC signaling protocol is now part of the Communication Gateway service. See [DOCUMENTATION_MIGRATION_REPORT.md](./reports/DOCUMENTATION_MIGRATION_REPORT.md) for details.

## AgentCall MCP

**Version:** 1.0 (Historical)
**Status:** Deprecated

---

## 1. MCP Tool Definitions

The MCP server exposes tools following the Model Context Protocol specification. Each tool is available to any MCP-compatible client.

### 1.1 create_call

Initiates a voice call to a specified user.

```
Tool Name: create_call
Description: Request a real-time voice call with a human user

Input Schema:
{
  "type": "object",
  "required": ["user_id", "context"],
  "properties": {
    "user_id": {
      "type": "string",
      "description": "Unique identifier of the user to call"
    },
    "context": {
      "type": "object",
      "description": "Structured context describing why the call is needed",
      "properties": {
        "task_id": {
          "type": "string",
          "description": "Reference to the AI task needing input"
        },
        "reason": {
          "type": "string",
          "enum": ["clarification", "approval", "error", "input_required"],
          "description": "Category of why human input is needed"
        },
        "summary": {
          "type": "string",
          "description": "Brief explanation of what the agent needs"
        },
        "options": {
          "type": "array",
          "items": { "type": "string" },
          "description": "Pre-computed options for the user to choose from"
        }
      }
    },
    "priority": {
      "type": "string",
      "enum": ["low", "normal", "high", "urgent"],
      "default": "normal",
      "description": "Call priority level"
    },
    "timeout_seconds": {
      "type": "integer",
      "default": 30,
      "minimum": 10,
      "maximum": 300,
      "description": "Seconds to wait before auto-cancelling"
    }
  }
}

Output Schema:
{
  "type": "object",
  "required": ["call_id", "status"],
  "properties": {
    "call_id": {
      "type": "string",
      "description": "Unique call identifier (UUID v7)"
    },
    "status": {
      "type": "string",
      "enum": ["ringing", "queued", "rejected", "offline"],
      "description": "Initial call status"
    },
    "estimated_wait_seconds": {
      "type": "integer",
      "description": "Estimated time until user answers (if queued)"
    }
  }
}
```

### 1.2 resume_task

Retrieve the result of a completed call and resume the AI task.

```
Tool Name: resume_task
Description: Get the structured response from a completed call and resume execution

Input Schema:
{
  "type": "object",
  "required": ["call_id"],
  "properties": {
    "call_id": {
      "type": "string",
      "description": "Call identifier returned from create_call"
    },
    "wait_for_completion": {
      "type": "boolean",
      "default": false,
      "description": "If true, blocks until call is complete (long timeout)"
    }
  }
}

Output Schema:
{
  "type": "object",
  "required": ["status", "result"],
  "properties": {
    "status": {
      "type": "string",
      "enum": ["completed", "cancelled", "timed_out", "in_progress"]
    },
    "result": {
      "type": "object",
      "properties": {
        "transcript_summary": {
          "type": "string",
          "description": "LLM-generated summary of the conversation"
        },
        "user_response": {
          "type": "string",
          "description": "Direct answer to the AI's question"
        },
        "decision": {
          "type": "string",
          "description": "User's decision (if applicable)"
        },
        "selected_option": {
          "type": "string",
          "description": "Option chosen by user (if options were provided)"
        },
        "sentiment": {
          "type": "string",
          "enum": ["positive", "neutral", "negative", "urgent"],
          "description": "User sentiment detected during call"
        },
        "action_items": {
          "type": "array",
          "items": { "type": "string" },
          "description": "Action items agreed upon during the call"
        },
        "full_transcript": {
          "type": "string",
          "description": "Complete conversation transcript (if user consent given)"
        },
        "duration_seconds": {
          "type": "integer",
          "description": "Actual call duration"
        }
      }
    }
  }
}
```

### 1.3 cancel_call

Cancel an active or ringing call.

```
Tool Name: cancel_call
Description: Cancel a pending or active call

Input Schema:
{
  "type": "object",
  "required": ["call_id"],
  "properties": {
    "call_id": { "type": "string" },
    "reason": {
      "type": "string",
      "enum": ["resolved", "timeout", "error", "user_requested"],
      "default": "resolved"
    }
  }
}

Output Schema:
{
  "type": "object",
  "properties": {
    "status": { "type": "string", "enum": ["cancelled", "already_ended"] }
  }
}
```

### 1.4 query_presence

Check if a user is available for calls.

```
Tool Name: query_presence
Description: Check a user's current availability for voice calls

Input Schema:
{
  "type": "object",
  "required": ["user_id"],
  "properties": {
    "user_id": { "type": "string" }
  }
}

Output Schema:
{
  "type": "object",
  "properties": {
    "status": {
      "type": "string",
      "enum": ["online", "away", "busy", "offline"]
    },
    "last_seen": {
      "type": "string",
      "format": "date-time",
      "description": "ISO 8601 timestamp of last activity"
    },
    "available_devices": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "platform": { "type": "string", "enum": ["android", "ios", "web"] },
          "push_enabled": { "type": "boolean" },
          "callkit_available": { "type": "boolean" }
        }
      }
    },
    "do_not_disturb": {
      "type": "boolean",
      "description": "User has DND enabled"
    }
  }
}
```

### 1.5 notify_completion

Notify a user about a completed task.

```
Tool Name: notify_completion
Description: Send a non-urgent notification that a task has completed

Input Schema:
{
  "type": "object",
  "required": ["user_id", "summary"],
  "properties": {
    "user_id": { "type": "string" },
    "summary": { "type": "string", "maxLength": 500 },
    "details": {
      "type": "object",
      "description": "Optional structured result",
      "properties": {
        "task_id": { "type": "string" },
        "duration_seconds": { "type": "integer" },
        "artifacts": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": { "type": "string" },
              "type": { "type": "string" },
              "url": { "type": "string" }
            }
          }
        }
      }
    },
    "priority": {
      "type": "string",
      "enum": ["low", "normal"],
      "default": "normal"
    }
  }
}

Output Schema:
{
  "type": "object",
  "properties": {
    "status": { "type": "string", "enum": ["delivered", "queued"] },
    "notification_id": { "type": "string" }
  }
}
```

---

## 2. Backend REST API (Internal)

These endpoints are consumed by the MCP Server and are not exposed externally.

### 2.1 Base URL

Internal Docker network: `http://backend-api:4000/api/v1`

### 2.2 Authentication

All requests include an `Authorization: Bearer <service_token>` header. Service tokens are pre-shared secrets configured via environment variables.

### 2.3 Endpoints

#### POST /api/v1/calls

Create a new call session.

```json
Request:
{
  "user_id": "uuid",
  "agent_id": "uuid",
  "context": { ... },
  "priority": "normal",
  "timeout_seconds": 30
}

Response 201:
{
  "call_id": "uuid-v7",
  "status": "ringing",
  "expires_at": "2026-07-07T20:00:00Z"
}
```

#### GET /api/v1/calls/:call_id

Get call status and result.

```json
Response 200:
{
  "call_id": "uuid",
  "status": "completed",
  "user_id": "uuid",
  "agent_id": "uuid",
  "created_at": "ISO8601",
  "connected_at": "ISO8601",
  "ended_at": "ISO8601",
  "duration_seconds": 45,
  "result": { ... },
  "quality_metrics": {
    "avg_jitter_ms": 12,
    "avg_rtt_ms": 45,
    "packet_loss_pct": 0.3
  }
}
```

#### POST /api/v1/calls/:call_id/cancel

Cancel a call.

```json
Request: { "reason": "resolved" }
Response 200: { "status": "cancelled" }
```

#### GET /api/v1/users/:user_id/presence

Query user presence.

```json
Response 200:
{
  "user_id": "uuid",
  "status": "online",
  "last_seen": "ISO8601",
  "devices": [...],
  "dnd": false
}
```

#### POST /api/v1/notifications

Send a push notification.

```json
Request:
{
  "user_id": "uuid",
  "type": "call_incoming" | "task_complete",
  "payload": { ... }
}

Response 200:
{
  "status": "delivered",
  "device_targets": 2
}
```

#### POST /api/v1/devices/register

Register a device for push notifications.

```json
Request:
{
  "user_id": "uuid",
  "platform": "android" | "ios",
  "push_token": "fcm-or-apns-token",
  "device_name": "Pixel 9",
  "app_version": "1.0.0"
}

Response 201:
{
  "device_id": "uuid",
  "status": "registered"
}
```

---

## 3. WebRTC Signaling Protocol

### 3.1 WebSocket Endpoint

`wss://signaling.agentcall.com/ws?token=<jwt>&call_id=<call_id>`

### 3.2 Message Format

All messages are JSON with the following envelope:

```json
{
  "type": "message_type",
  "payload": { ... },
  "timestamp": "ISO8601"
}
```

### 3.3 Message Types

#### Client → Server

| Type | Payload | Description |
|------|---------|-------------|
| `join_call` | `{ call_id, user_id, role }` | Join the signaling room |
| `offer` | `{ sdp: RTCSessionDescription }` | WebRTC SDP offer |
| `answer` | `{ sdp: RTCSessionDescription }` | WebRTC SDP answer |
| `ice_candidate` | `{ candidate: RTCIceCandidate }` | ICE candidate |
| `mute` | `{ muted: boolean }` | Toggle mute state |
| `hangup` | `{}` | End the call |

#### Server → Client

| Type | Payload | Description |
|------|---------|-------------|
| `room_joined` | `{ call_id, participants }` | Confirmed room join |
| `participant_joined` | `{ user_id, role }` | New participant |
| `participant_left` | `{ user_id }` | Participant left |
| `offer` | `{ sdp: RTCSessionDescription }` | Incoming SDP offer |
| `answer` | `{ sdp: RTCSessionDescription }` | Incoming SDP answer |
| `ice_candidate` | `{ candidate: RTCIceCandidate }` | Incoming ICE candidate |
| `mute_changed` | `{ user_id, muted }` | Mute state change |
| `peer_muted` | `{ user_id, muted }` | Peer mute notification |
| `error` | `{ code, message }` | Protocol error |

### 3.4 Signaling Flow

```
Mobile App                Signaling Server              AI Agent
    │                           │                          │
    │── join_call ─────────────>│                          │
    │<── room_joined ───────────│                          │
    │                           │                          │
    │      (Server triggers call connection)               │
    │                           │                          │
    │<── offer ─────────────────│                          │
    │── answer ────────────────>│                          │
    │<── ice_candidate ─────────│                          │
    │── ice_candidate ─────────>│                          │
    │                           │                          │
    │    (WebRTC peer connection established)              │
    │    (Audio flows directly P2P or via TURN)            │
    │                           │                          │
    │── hangup ────────────────>│                          │
    │                           │── call_ended ──────────> │
```

---

## 4. Push Notification Payloads

### 4.1 Incoming Call (Android - FCM)

```json
{
  "message": {
    "token": "device-fcm-token",
    "data": {
      "type": "call_incoming",
      "call_id": "uuid",
      "caller": "AI Agent - Task #42",
      "context_summary": "Need approval to deploy to production",
      "priority": "high"
    },
    "android": {
      "priority": "high",
      "ttl": "30s",
      "notification": {
        "title": "Incoming AI Call",
        "body": "Agent needs your input: Need approval to deploy...",
        "channel_id": "incoming_calls"
      }
    }
  }
}
```

### 4.2 Incoming Call (iOS - APNs via PushKit)

```json
{
  "aps": {
    "alert": { "title": "Incoming AI Call", "body": "..." },
    "badge": 1,
    "sound": "call.caf",
    "mutable-content": 1
  },
  "call_id": "uuid",
  "caller_name": "AI Agent",
  "handle": "agent-42",
  "has_video": false
}
```

### 4.3 Task Complete (Both platforms)

```json
{
  "type": "task_complete",
  "title": "Task Complete",
  "body": "AI has finished: Code review complete, 3 issues found",
  "task_id": "uuid",
  "summary": "Code review complete, 3 issues found"
}
```
