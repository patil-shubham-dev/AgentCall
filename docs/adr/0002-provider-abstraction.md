# ADR-0002: Provider Abstraction

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

AgentCall must support multiple AI providers (ChatGPT, Claude, Gemini, local LLMs, custom agents) without modifying core architecture. Each provider has different authentication, communication protocols, and capabilities.

## Decision

Introduce a `ProviderAdapter` interface that all AI providers must implement:

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

Each provider gets:
- Isolated history, transcripts, sessions, callbacks, permissions
- Independent authentication (OAuth, API key, or none)
- Its own adapter implementation in `backend/src/providers/adapters/`

The Provider Registry manages all registered providers and provides a unified lookup interface.

## Alternatives Considered

- **No abstraction layer**: Direct integration per provider. Rejected because it creates tight coupling and duplicate logic.
- **Webhook-only**: All providers must support webhooks. Rejected because not all providers support webhooks (e.g., local CLI tools).
- **MCP-only**: Only support MCP-native clients. Rejected because it excludes ChatGPT Actions, Gemini, and custom API clients.

## Consequences

**Positive:**
- Adding a new provider requires only a new adapter class
- Core services never reference provider-specific code
- Provider isolation is guaranteed by architecture
- Testing is straightforward with mock adapters

**Negative:**
- Adapter interface must be maintained as providers evolve
- Some providers may not fit cleanly into the adapter model
- Additional development overhead for each new provider

## Tradeoffs

- More upfront engineering for adapter interface vs. direct integration
- Standardization may limit some provider-specific capabilities

## Future Work

- Define versioned provider capabilities (e.g., provider announces what it supports)
- Auto-discovery of provider capabilities via MCP
- Provider health monitoring and automatic failover
