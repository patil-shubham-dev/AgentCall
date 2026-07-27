# ⚠️ HISTORICAL REFERENCE — System Architecture

> **This document describes the original aspirational architecture specification.**
> **It does NOT describe the current VoiceBridge v1.0 implementation.**
>
> VoiceBridge v1.0 is a single-process monolithic service (not 11 microservices).
> For the actual architecture, see [ARCHITECTURE_BASELINE.md](./ARCHITECTURE_BASELINE.md).
> For the actual API, see [API_SPEC.md](./API_SPEC.md).
> For the actual code structure, see `backend/src/`.

---

# Purpose

This document defines the complete architecture of AgentCall and serves as the architectural source of truth for the project.

It covers:

- Platform architecture
- Domain model
- Service boundaries
- Data ownership
- Communication flow
- Provider abstraction
- Scalability strategy
- Future extensibility

---

# Architecture Philosophy

> **AI owns intelligence.**
>
> **AgentCall owns communication.**
>
> **Humans own decisions.**

The AI is responsible for reasoning, planning, context, memory and conversation.

AgentCall is responsible for communication, routing, presence, notifications, callbacks, sessions and devices.

---

# High-Level Architecture

```text
                AI Providers
 ChatGPT • Claude • Gemini • Cursor • Local LLMs
                    │
                    ▼
      MCP • REST • OpenAPI • Actions
                    │
                    ▼
            AgentCall Runtime
 Authentication
 Provider Registry
 Session Manager
 Call Manager
 Presence Engine
 Notification Engine
 Callback Engine
 Device Router
 History Service
 Communication Gateway
 Event Bus
                    │
                    ▼
      Android • Future iOS • Desktop • Web
                    │
                    ▼
                  Human
```

---

# Core Principles

- Single Responsibility
- AI Agnostic
- Provider Agnostic
- Device Agnostic
- Communication First
- Event Driven

---

# Runtime Services

- Authentication Service
- Provider Registry
- Session Manager
- Call Manager
- Presence Engine
- Notification Engine
- Callback Engine
- Device Router
- History Service
- Communication Gateway
- Event Bus

---

# Domain Model

```text
User
 └── Device
      └── Provider
            └── Session
                  └── Call
                        └── Transcript
                              └── Message
```

## User

Owns identity, preferences and connected devices.

## Device

Represents a communication endpoint.

## Provider

Represents an AI such as ChatGPT, Claude or Gemini.

Each provider owns independent sessions, history and callbacks.

## Session

Represents a long-lived relationship between one provider and one user.

## Call

Represents one communication event.

Lifecycle:

Created → Pending → Ringing → Answered → Active → Completed / Cancelled / Missed

---

# Event Driven Architecture

Every significant action becomes an event.

Examples:

- CallCreated
- CallAccepted
- CallEnded
- PresenceChanged
- NotificationSent
- ProviderConnected

---

# Backend Structure

```text
backend/
├── auth/
├── calls/
├── providers/
├── presence/
├── notifications/
├── history/
├── devices/
├── gateway/
├── common/
└── database/
```

---

# Mobile App Modules

- Authentication
- Calls
- History
- Providers
- Notifications
- Settings
- Presence
- Audio
- Network

The mobile app is a communication endpoint, not an AI assistant.

---

# Data Ownership Rules

AgentCall may:

- Store
- Forward
- Authenticate
- Deliver

AgentCall must never:

- Rewrite prompts
- Perform reasoning
- Enrich AI output
- Generate summaries

---

# Scalability

Designed for:

- Stateless APIs
- PostgreSQL
- Redis
- Horizontal Scaling
- Message Queues

---

# Future Expansion

The architecture must support future endpoints including:

- Desktop
- Browser
- Wearables
- Smart Speakers
- Vehicle Systems

without redesigning the runtime.

---

# Design Rule

If a feature belongs to AI reasoning, it does not belong in AgentCall.

If a feature belongs to communication, it belongs in AgentCall.

---

# Summary

> AgentCall is an event-driven communication runtime that enables any AI provider to securely communicate with humans through a provider-agnostic and device-agnostic platform while remaining completely separate from AI reasoning, memory and conversation management.
