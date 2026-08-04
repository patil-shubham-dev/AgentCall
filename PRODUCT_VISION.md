# AgentCall
> **The Professional Communication Bridge Between AI and Humans**
>
> **Mission**
>
> Build the universal communication layer that enables any AI to securely communicate with humans from anywhere, allowing autonomous AI systems to continue working even when the user is away from their computer.
>
> **System architecture:** See [SYSTEM_ARCHITECTURE.md](./SYSTEM_ARCHITECTURE.md)
> **API contract:** See [docs/archive/API_SPEC.md](./docs/archive/API_SPEC.md)

---

# Vision

AgentCall is **not an AI assistant**.

AgentCall is **not another chatbot**.

AgentCall is **not another MCP client**.

AgentCall is a communication platform.

Just as:

- GitHub provides repositories
- Stripe provides payments
- Twilio provides communication APIs

**AgentCall provides communication capabilities for AI.**

Its responsibility is to allow any AI to reach a human whenever real human judgement is required.

---

# Philosophy

The AI already knows how to think.

The AI already knows the task.

The AI already has the conversation context.

AgentCall should never duplicate that.

Instead, AgentCall provides one thing:

> **Communication**

Nothing more.

Nothing less.

---

# Core Principle

The AI owns intelligence.

AgentCall owns communication.

```
                AI

Reasoning
Planning
Memory
Context
Task Execution

        │

        ▼

──────────────────────────────────
         AgentCall
──────────────────────────────────

Voice
Notifications
Presence
Routing
Callbacks
Device Management
Conversation Transport

        │

        ▼

      Human
```

---

# Product Identity

AgentCall is a platform that allows AI systems to communicate with humans through real-time voice interactions, notifications and future communication channels.

It is completely AI-provider agnostic.

The same infrastructure should work for:

- ChatGPT
- Claude
- Gemini
- Cursor
- Claude Code
- OpenCode
- Cline
- Codex
- Future AI systems
- Local LLMs

without modifying the core architecture.

---

# Product Goals

AgentCall should allow AI systems to:

- Call a human
- Ask follow-up questions
- Wait for a response
- Resume the original task
- Retry later
- Schedule callbacks
- Detect whether the user is online
- Detect available devices
- Send notifications
- Receive voice responses
- Receive typed responses

without owning the AI conversation itself.

---

# What AgentCall IS NOT

AgentCall is NOT:

- A chatbot
- An AI assistant
- An LLM
- A memory system
- A prompt manager
- A task manager
- A coding assistant
- A replacement for ChatGPT
- A replacement for Claude

---

# The User Experience

The user never chats inside AgentCall.

The user continues working inside:

- ChatGPT
- Claude
- Gemini
- Cursor
- Claude Code
- OpenCode

When the AI requires clarification:

```
User

↓

ChatGPT

↓

"Continue working"

↓

AI continues autonomously

↓

AI requires clarification

↓

AgentCall

↓

Phone rings

↓

Conversation

↓

AI continues working
```

The user returns to ChatGPT after the call.

---

# Core Architecture

```
             AI Providers

 ChatGPT
 Claude
 Gemini
 Cursor
 OpenCode
 Codex
 Local LLMs

           │

           ▼

────────────────────────────────────
        AgentCall Platform
────────────────────────────────────

Authentication

Provider Adapter Layer

MCP

OpenAPI

REST

Function Calling

Actions

Webhooks

────────────────────────────────────

Communication Engine

Voice Engine

Notification Engine

Presence Engine

Routing Engine

Session Engine

History Engine

────────────────────────────────────

AgentCall Mobile App

────────────────────────────────────

Human
```

---

# AI Provider Philosophy

Every provider is isolated.

Each provider has its own:

- history
- transcript
- sessions
- callbacks
- permissions

Example:

```
ChatGPT

    History

Claude

    History

Gemini

    History

Cursor

    History
```

Global settings remain shared.

---

# Shared Settings

Settings should be universal.

Examples:

- Voice preferences
- Notification preferences
- Callback behaviour
- Retry interval
- Preferred device
- Theme
- Language
- Microphone settings
- Speaker settings

These should affect every provider.

---

# Context Handling

AgentCall should never become another memory platform.

The AI already has:

- project
- files
- prompts
- memory
- reasoning
- chat history

AgentCall only receives:

```
Reason

Question

Priority

Summary

Metadata
```

Nothing more.

---

# AgentCall Mobile App

The app exists only as the user's communication endpoint.

It is NOT another AI assistant.

---

## Home

Shows:

- Online status
- Connected AI providers
- Connected devices
- Active call
- Recent notifications

---

## Calls

Displays:

- Active calls
- Previous calls
- Missed calls
- Scheduled callbacks

---

## Transcript

Every call contains:

AI Message

↓

Your Voice

↓

AI Response

↓

Your Voice

or

AI Message

↓

Your Text Reply

↓

AI Response

---

## Notifications

Shows:

Missed call notifications

Callback reminders

AI requests

Errors

System alerts

---

## Connected Providers

Displays:

ChatGPT

Claude

Gemini

Cursor

OpenCode

etc.

Each provider can be:

- Connected
- Revoked
- Re-authorized

---

## Settings

Voice

Notifications

Retry Behaviour

Callback Behaviour

Presence

Theme

Permissions

Privacy

Logs

Developer Mode

---

# During a Call

The experience should be extremely simple.

```
────────────────────────────

ChatGPT

Repository Review

────────────────────────────

AI Speaking...

────────────────────────────

Transcript

────────────────────────────

Repeat

Talk

Type Reply

Cancel

────────────────────────────
```

---

# Voice Flow

1. AI speaks.

2. User listens.

3. User presses Talk.

4. User speaks.

5. User presses Stop.

6. Voice is sent.

7. AI thinks.

8. AI responds.

Repeat.

---

# Typed Response

Sometimes the user cannot speak.

Instead:

```
AI asks question

↓

User opens transcript

↓

Types answer

↓

Send

↓

AI continues
```

---

# Cancel Behaviour

If user cancels:

AgentCall should automatically:

Notify the AI

↓

AI decides next step

↓

AgentCall sends notification

Example:

```
ChatGPT attempted to call you.

Reason:

Need approval before deleting database.

Retry scheduled in 30 minutes.
```

---

# Presence System

AI should know:

Online

Offline

Busy

In Call

Do Not Disturb

Sleeping (future)

Driving (future)

---

# Callback Engine

The user should configure:

Retry after:

- 5 min
- 10 min
- 30 min
- 1 hour
- Custom

Maximum retries

Retry only if urgent

Silent retry

Notification only

---

# Notification Engine

AgentCall notifications should support:

Incoming call

Missed call

Callback

Action required

Task completed

AI waiting

---

# Authentication

Each provider connects independently.

Example:

```
ChatGPT

↓

OAuth

↓

AgentCall

↓

Permission Granted
```

Claude has its own connection.

Gemini has its own connection.

Every provider remains isolated.

---

# Communication APIs

AgentCall should expose every free communication standard available.

Priority:

1. MCP

2. OpenAPI

3. REST

4. Function Calling

5. Actions

6. Webhooks

7. SSE

8. WebSocket

No vendor lock-in.

---

# Design Principles

Professional

Simple

Fast

Reliable

Extensible

AI Agnostic

Provider Agnostic

Privacy First

Free First

Open Source

---

# Free First Philosophy

Always prefer free solutions.

Examples:

- Free hosting
- Open standards
- MCP
- OpenAPI
- OAuth
- Local speech
- WebRTC
- Self-hosting
- Docker

Paid integrations should always remain optional.

---

# Future Communication Channels

The architecture should support additional endpoints without redesign.

Examples:

- Desktop
- Web
- Wearables
- Smart Speakers
- Smart Watches
- Browser
- Car Systems
- Voice Assistants

---

# Long-Term Vision

Today:

AI → Human Voice Calls

Tomorrow:

AI → Humans Anywhere

Regardless of:

- Device
- Operating System
- AI Provider
- Location

AgentCall should become the universal communication layer between AI systems and humans.

---

# One Sentence Definition

> **AgentCall is an open, AI-agnostic communication platform that enables any AI to securely reach humans through voice, notifications, and future communication channels, allowing autonomous AI systems to continue working even when the user is away from their computer.**

---

# Guiding Principle

> **AI should think.**
>
> **Humans should decide.**
>
> **AgentCall should connect them.**