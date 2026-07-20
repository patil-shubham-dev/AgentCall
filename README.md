# VoiceBridge — AI-to-Human Voice Calling

**Zero paid APIs. Zero cloud services. Zero databases. Just AI calling your phone.**

VoiceBridge lets AI agents (Claude, Cursor, OpenCode, ChatGPT) call you on your phone using WebRTC, speak with emotion, understand your spoken replies via local Whisper STT, and handle interruptions — all running on your laptop and Android phone with nothing but a WiFi connection.

## What it does

1. An AI agent needs your input → calls your phone via the MCP server
2. AI speaks to you with emotion tags (`[calm]`, `[urgent]`, `[excited]`, `[thoughtful]`)
3. AI inserts natural breathing pauses and filler words ("um", "well", "actually")
4. You can **interrupt** the AI mid-sentence — say "wait", "repeat", or "call me back in 10"
5. Your spoken responses are transcribed locally via Whisper (no API calls)
6. The AI remembers the conversation and can resume where it left off

## Architecture

```
┌──────────────────────┐      MCP (stdio)      ┌─────────────────┐
│    AI Agent          │ ◄──────────────────► │   MCP Server    │
│  (Claude / OpenCode /│                      │   (Node.js)     │
│   Cursor / ChatGPT)  │                      └────────┬────────┘
└──────────────────────┘                               │ REST API (localhost:4000)
                                                        ▼
┌──────────────────────────────────────────────────────────────────┐
│                     Backend (Node.js / Fastify)                    │
│  ┌──────────┐  ┌────────────┐  ┌──────────┐  ┌────────────────┐ │
│  │ Signaling │  │ VoiceBridge│  │   REST   │  │  Whisper STT   │ │
│  │  Server   │  │  Service   │  │  Routes  │  │  (local CPU)   │ │
│  └──────────┘  └────────────┘  └──────────┘  └────────────────┘ │
│  ┌──────────────────────────────────────────────────────────────┐ │
│  │              In-Memory Storage (no database)                  │ │
│  └──────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
            │ WebSocket + WebRTC
            ▼
┌──────────────────────┐
│   Android Phone      │
│  ┌────────────────┐  │
│  │ Emotion TTS    │  │  Built-in TextToSpeech (free)
│  │ (calm/urgent/  │  │  → adjusts pitch + speed per emotion
│  │  excited/      │  │
│  │  thoughtful)   │  │
│  ├────────────────┤  │
│  │ Barge-in       │  │  Microphone monitors while AI speaks
│  │ AudioRecord    │  │  → RMS > 3500 for 500ms = interrupt
│  ├────────────────┤  │
│  │ Chat UI        │  │  Emotion-colored bubbles + waveform
│  ├────────────────┤  │
│  │ "Later"        │  │  Schedule callback 5/10/15/30/60 min
│  └────────────────┘  │
└──────────────────────┘
```

## Repo Structure

```
├── backend/          — API server + voice engine + signaling (Node.js/TypeScript)
│   └── src/voicebridge/   — Emotion engine, STT, barge-in detection
├── mcp-server/       — MCP tool server (5 tools for AI agents)
├── mobile/
│   ├── android/      — Android app (Kotlin, Jetpack Compose)
│   └── ios-archived/ — iOS code frozen (not maintained)
├── infra/            — Docker Compose, Caddyfile, coturn config
└── docs/             — Design documents
```

## Quick Start

### Prerequisites

- Node.js 18+
- An Android phone (emulator or device) on the same WiFi as your laptop
- Optional: a free [ngrok](https://ngrok.com) account if you want ChatGPT to call you

### Backend

```bash
cd backend
cp ../.env.example .env
npm install
npm run dev
# Server starts on http://localhost:4000
# Verify: curl http://localhost:4000/api/v1/health
```

### Android App

1. Open `mobile/android/` in Android Studio
2. Update `ApiClient.BASE_URL` to your laptop's local IP (e.g., `http://192.168.1.42:4000`)
3. Build and deploy to your phone
4. Both devices must be on the same WiFi

### MCP Server (for AI agents)

```bash
cd mcp-server
npm install
npm run build
```

Then connect your AI agent:

**Claude Code:**
```bash
claude --mcp-servers "voicebridge=node /path/to/mcp-server/dist/index.js"
```

**OpenCode:**
```bash
opencode --mcp-server "voicebridge=node /path/to/mcp-server/dist/index.js"
```

**Cursor / VS Code:**
Add to `.cursor/mcp.json` or `.vscode/mcp.json`:
```json
{
  "mcpServers": {
    "voicebridge": {
      "command": "node",
      "args": ["/path/to/mcp-server/dist/index.js"]
    }
  }
}
```

**ChatGPT:**
Use ChatGPT Custom GPTs with the [VoiceBridge OpenAPI spec](voicebridge-openapi.json) via ngrok.

### Test It

```bash
# Create a call
curl -X POST http://localhost:4000/api/v1/calls \
  -H "Content-Type: application/json" \
  -d '{"context": {"reason": "input_required", "summary": "[urgent] The build is failing!"}}'
```

Your phone rings. AI speaks. You can interrupt, ask questions, or schedule a callback.

## MCP Tools

| Tool | Description |
|------|-------------|
| `create_call` | Call the human — provide context and emotion tags |
| `send_message` | Speak to the human mid-call (with emotion) |
| `get_transcript` | Read what the human said so far |
| `complete_call` | End the call and save results |
| `cancel_call` | Cancel an unanswered or scheduled call |

## Emotion Tags

Wrap text in emotion tags to control the AI's voice:

| Tag | Effect | Use Case |
|-----|--------|----------|
| `[calm]` | Slow, gentle, reassuring | Explanations, summaries |
| `[urgent]` | Fast, higher pitch, alert | Failures, deadlines |
| `[excited]` | Energetic, upbeat | Wins, celebrations |
| `[thoughtful]` | Slow, quiet, deliberate | Complex topics, trade-offs |

The Android TTS engine adjusts pitch (±30%) and speech rate (±30%) automatically.

## Voice Features

- **Breathing pauses:** Random 300–700ms pauses between sentences
- **Filler words:** "um", "uh", "hmm", "well", "actually" at 15–60% probability
- **Barge-in:** Say "wait" → AI pauses; "repeat" → rephrases; "call me back in X" → schedules callback
- **Natural cadence:** Emotion-aware pacing (urgent = faster, thoughtful = slower)

## Stack

| Component | Technology | Cost |
|-----------|-----------|------|
| Backend   | Node.js / TypeScript / Fastify | Free |
| STT       | `@xenova/transformers` + Whisper base (local CPU) | Free |
| TTS       | Android TextToSpeech (built-in) | Free |
| Signaling | WebSocket (Node.js `ws`) | Free |
| Media     | WebRTC (native) | Free |
| Storage   | In-memory (no database) | Free |
| AI Integration | MCP Protocol (stdio/SSE) | Free |

## Design Philosophy

- **No paid APIs.** Not one. The entire system works on local WiFi with zero internet dependency.
- **Single process.** No Docker, no PostgreSQL, no Redis, no Docker Compose needed in development.
- **Solo-dev friendly.** One person can understand and modify the entire codebase.
- **Emotion matters.** Voice is emotional. Even free TTS can convey calm, urgency, excitement, and thoughtfulness.
- **Interruptible.** Voice conversations are interactive — the AI should shut up when you need to think.

## License

MIT
