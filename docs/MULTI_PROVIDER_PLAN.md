# AgentCall — Multi-AI Provider Integration Plan

> **Canonical references:** [PRODUCT_VISION.md](../PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md)

---

## Vision

Allow any AI provider (ChatGPT, Claude, Gemini, local Ollama, custom agents) to communicate with humans through a single unified interface. AgentCall is provider-agnostic — the same infrastructure works for any AI without modifying core architecture.

---

## Architecture

```
                    AI Provider Layer
  ChatGPT | Claude | Gemini | Ollama | Custom Agents
                      │
          Provider Adapter (abstraction)
    Auth (API key / OAuth / none)
    Rate limiting
    Request/response normalization
    Webhook registration
                      │
             Communication Runtime
    Session Manager | Call Manager | Presence Engine
    Notification Engine | Callback Engine | Device Router
                      │
               MCP Tool Interface
    create_call | send_message | get_transcript
    complete_call | cancel_call
    query_presence | resume_task | notify_completion
                      │ HTTP REST
                      ▼
               Backend
    WebSocket signaling
    Call session management
                      │ WebSocket
                      ▼
             Mobile App (Android)
```

> See [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) for the complete runtime architecture.

---

## Provider Integration Matrix

| Provider | MCP Native? | SSE Support? | Priority |
|----------|------------|-------------|----------|
| **OpenCode** | ✅ Yes (stdio) | ✅ Yes | P0 |
| **Claude Code** | ✅ Yes (stdio) | ✅ Yes | P0 |
| **Cline** | ✅ Yes (stdio) | ✅ Yes | P0 |
| **Cursor** | ✅ Yes (stdio) | ✅ Yes | P0 |
| **ChatGPT Desktop** | ⚠️ Beta | ⚠️ Beta | P1 |
| **ChatGPT (Custom GPT)** | ❌ No | ✅ Yes (via MCP SSE) | P1 |
| **Claude.ai** | ❌ No | ✅ Yes (via MCP SSE) | P1 |
| **Gemini** | ❌ No | ❌ No | P2 |
| **OpenAI API** | ❌ No | ❌ No | P2 |
| **Ollama (local)** | ❌ No | ❌ No | P3 |

---

## Implementation Phases

### Phase 1: MCP-Native Clients (Already Working)
**Target:** OpenCode, Claude Code, Cline, Cursor

The MCP server supports stdio and StreamableHTTP transports. See [API_SPEC.md](../API_SPEC.md) for tool definitions.

### Phase 2: Remote MCP + SSE (Web UI Integration)
**Target:** ChatGPT Actions, Claude.ai, web-based clients

**Needed:**
- Deploy MCP server to public HTTPS URL
- Create provider-specific manifests (OpenAPI spec for ChatGPT, MCP definition for Claude)

### Phase 3: REST API for Non-MCP Providers
**Target:** Gemini, Ollama, custom agents

All REST endpoints per [API_SPEC.md](../API_SPEC.md).

### Phase 4: Provider Abstraction Layer
**Target:** All providers through a single adapter

Proposed adapter interface:
```typescript
interface ProviderAdapter {
  name: string;
  supportedModes: ('sync' | 'async' | 'webhook')[];
  createCall(params: CreateCallParams): Promise<CallResult>;
  sendAndRespond(params: SendAndRespondParams): Promise<AIResponse>;
  registerWebhook(webhookUrl: string): Promise<void>;
  normalizeError(error: unknown): ProviderError;
}
```

---

## Security Considerations

| Concern | Mitigation |
|---------|-----------|
| Rogue AI agents making calls | Per-agent auth tokens + rate limits |
| AI impersonating users | Signed call context |
| Call eavesdropping | WSS + HTTPS only |
| Provider API key leaks | Env vars, never logged |
| Async webhook spoofing | Webhook secret verification (HMAC) |

---

## Provider Adapter Interface (Proposed)

Concrete adapters to implement:
- `MCPAdapter` — for stdio/SSE MCP clients
- `OpenAIAdapter` — for OpenAI API (function calling)
- `ChatGPTAdapter` — for ChatGPT Actions (webhook-based)
- `ClaudeAdapter` — for Claude API (tool use)
- `GeminiAdapter` — for Gemini API
- `OllamaAdapter` — for local models
