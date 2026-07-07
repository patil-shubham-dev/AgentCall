# AgentCall MCP

**AI-to-Human voice calling via MCP protocol.**

AgentCall lets AI agents initiate real-time voice calls with human users over WebRTC, using the Model Context Protocol (MCP). Built for autonomous workflows that need human input — clarifications, approvals, error recovery, or urgent notifications.

## Architecture

```
┌──────────────┐     MCP (stdio)     ┌─────────────┐     WebSocket     ┌──────────┐
│  AI Agent    │ ──────────────────► │  MCP Server  │ ───────────────► │ Signaling │
│  (Claude,    │ ◄────────────────── │  (Node.js)   │ ◄─────────────── │  Server   │
│   Cursor,    │                     └──────┬──────┘                  └──────────┘
│   etc.)      │                            │ REST API
└──────────────┘                     ┌──────▼──────┐     Push (FCM/APNs) ┌──────────┐
                                     │  Backend    │ ──────────────────► │  Mobile  │
                                     │  (Fastify)  │ ◄────────────────── │  Apps    │
                                     └──────┬──────┘     WebRTC media   └──────────┘
                                            │
                               ┌────────────┼────────────┐
                               ▼            ▼            ▼
                          PostgreSQL     Redis       Coturn
                                                   (STUN/TURN)
```

## Repo Structure

```
├── backend/         — API server, signaling, auth, push (Node.js/TypeScript)
├── mcp-server/      — MCP tool server (stdio transport)
├── mobile/
│   └── android/     — Android app (Kotlin, Compose, WebRTC)
├── infra/           — Docker Compose, Caddyfile, coturn config
└── docs/            — Design documents (architecture, API, DB, security, etc.)
```

## Quick Start

```bash
# 1. Generate JWT key pair
mkdir -p backend/keys
openssl genrsa -out backend/keys/jwt_private.pem 2048
openssl rsa -in backend/keys/jwt_private.pem -pubout -out backend/keys/jwt_public.pem

# 2. Configure environment
cp .env.example .env
# Edit .env with your secrets

# 3. Start all services
docker compose -f infra/docker-compose.yml up -d

# 4. Run database migrations
cd backend
npm run migrate

# 5. Verify health
curl https://your-domain.com/api/v1/health
```

## API

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/auth/login` | Sign in / create user |
| POST | `/api/v1/auth/refresh` | Refresh access token |
| POST | `/api/v1/calls` | Create a new AI-initiated call |
| GET | `/api/v1/calls/:id` | Get call details |
| POST | `/api/v1/calls/:id/cancel` | Cancel a call |
| POST | `/api/v1/calls/:id/complete` | Complete a call with results |
| GET | `/api/v1/calls` | List call history |
| GET | `/api/v1/users/:id/presence` | Get user presence |
| POST | `/api/v1/presence/heartbeat` | Send presence heartbeat |
| POST | `/api/v1/devices/register` | Register a device for push |
| GET | `/api/v1/turn/credentials` | Get STUN/TURN credentials |
| POST | `/api/v1/notifications` | Send a push notification |

## MCP Tools

| Tool | Description |
|------|-------------|
| `create_call` | Initiate a voice call to a human user |
| `resume_task` | Resume a workflow after call completes |
| `cancel_call` | Cancel an ongoing or pending call |
| `query_presence` | Check if a user is available for a call |
| `notify_completion` | Send a completion notification to a user |

## Stack

- **Backend:** Node.js / TypeScript, Fastify, PostgreSQL 16, Redis 7
- **Signaling:** WebSocket (Node.js `ws`)
- **Media:** WebRTC via coturn (self-hosted STUN/TURN)
- **Push:** Firebase Cloud Messaging (Android) + APNs (iOS)
- **Mobile:** Android (Kotlin / Jetpack Compose), iOS (Swift — coming soon)
- **Deployment:** Docker Compose on Linux VPS, Caddy reverse proxy with auto TLS
- **Auth:** JWT (RS256) + API keys + service tokens

## License

MIT
