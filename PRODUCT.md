# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

A developer or power user running autonomous AI coding agents (Claude Code,
Cursor, OpenCode, Codex, and similar) who must be reachable by phone when an
agent needs human judgement mid-task. This build is a solo-user app on a
single device (Realme RMX3867, Android). The user continues working in their
AI tool; the app exists only as the human-side communication endpoint.

## Product Purpose

AgentCall is the universal, AI-agnostic communication layer between AI systems
and humans: AI-initiated voice calls, typed replies, notifications, callbacks,
and presence, so autonomous agents keep working even when the user is away
from their computer. It is explicitly NOT an AI assistant, chatbot, or MCP
client — the AI owns intelligence, AgentCall owns communication.

## Positioning

A communication platform for AI, the way Twilio provides communication APIs:
any AI provider can reach a human for real-time voice judgement without owning
or duplicating the AI's conversation, memory, or reasoning. A neighboring
product could not truthfully copy "AI-independent, provider-agnostic, real
voice calls with presence and callback routing."

## Operating Context

- Agent dials the user via WebSocket signaling; the phone rings with an
  incoming-call screen (answer / decline / "later" with 5–60 min options).
- During a call the user talks (push-to-talk style Record) or types replies;
  the agent speaks back; a live transcript thread shows the conversation.
- While away from the computer, the user monitors agent presence
  (Online / Busy / Offline) and receives missed-call notifications.
- Quiet hours make calls ring silently in a configured window; decline and
  call-back-later message templates are editable in Settings.

## Capabilities and Constraints

Confirmed functionality (current Android app):
- Home: grid of agent profiles with presence status, call + profile entry.
- Calls: active call screen with status banner, context summary, transcript,
  quick replies, text input, waveform, Record / Mute / Speaker / Repeat
  controls, and End call.
- Settings: server connection status (URL, latency), caller tune (name, tone,
  style, reminder frequency), decline + call-back-later message templates
  (voice mail was removed — do not reintroduce), quiet hours, AI provider
  keys (OpenAI / Anthropic / DeepSeek), network info, privacy & data
  (export / delete), and about.
- Persistence: Room (profiles, call records), SharedPreferences (templates,
  quiet hours, keys), Hilt DI, Jetpack Compose + Material 3.
- Backend: Express/Fastify + PostgreSQL, MCP endpoint, WebSocket signaling,
  WebRTC voice, deployed to Render.

Hard constraint for this redesign: **visual/brand redesign only.** Every
existing button, screen, setting, and function must remain present and
working. No copy/text changes, no behavior or backend changes.

## Brand Commitments

Binding commitments stated by the user for this redesign:
- An entirely NEW design system generated from scratch — not a polish pass on
  the incumbent dark-navy/indigo/glass look. The old palette and typography
  are not preserved; no color-scheme or serif-header continuity is owed.
- The result must feel modern, futuristic, and distinctive — genuinely new,
  not "the same app with nicer spacing."
- A clear, consistent status color language is required (active / idle /
  busy / destructive), whatever colors the new system chooses.
- Nothing existing gets hidden, removed, or renamed; every button, screen,
  setting, and function stays reachable and working.
- No changes to product copy (decline / call-back-later template defaults).
- No behavior or backend changes. This is visual design only.
- Product name "AgentCall" and the "professional communication bridge
  between AI and humans" positioning remain.

## Evidence on Hand

- PRODUCT_VISION.md (product truth above is derived from it).
- SYSTEM_ARCHITECTURE.md, docs/v2/04-api-spec.md.
- Current Android UI source under mobile/android (theme, home, settings,
  call, incoming call, profile screens).
- User's written walkthrough of the current screens' appearance and
  inconsistencies (status text formats, pill semantics, button hierarchy).
- No logo, marketing site, or brand assets exist; the app has no imagery
  beyond generated avatars. Future design work must not fabricate
  testimonials, customers, benchmarks, or deployment claims.

## Product Principles

1. AI thinks, humans decide, AgentCall connects them — the app is the
   communication endpoint, never an assistant.
2. AI-provider agnostic: any provider (or none) can use the same surface.
3. Communication must be simple, fast, reliable, and privacy-first.
4. The human always keeps control: answer, decline, schedule later, quiet
   hours, and per-agent settings all stay one tap away.
5. Free-first and open: no vendor lock-in in architecture or design.

## Accessibility & Inclusion

- Material 3 on Android: touch targets ≥ 48×48dp with ≥ 8dp spacing;
  system Back gesture must always work.
- Text in sp (follows system font scale); dark theme is a first-class
  scheme; status must be readable without relying on color alone
  (icon/label pairing for status and controls).
