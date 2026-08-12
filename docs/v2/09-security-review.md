# 09 — Security Review

> **Deliverable:** 14 (security review)
> **Companion docs:** [04-api-spec.md](./04-api-spec.md) §1, [05-database-schema.md](./05-database-schema.md), [08-reliability-ops.md](./08-reliability-ops.md) §5

---

## 1. Scope and method

Reviewed against the v2 design as specified in this document set, and against the **live v1
implementation** it replaces/appends (auth & rate-limit facts below are read from current
`backend/src`). Method: trust boundaries → threat model (STRIDE) → per-asset controls →
verification checklist. Findings are labeled **MUST / SHOULD / NICE**.

---

## 2. Trust boundaries (assets)

```
            Trust zone A: AI / automation                           Trust zone B: Human device
   ┌─────────────────────────────────────────┐       ┌─────────────────────────────────────┐
   │  MCP client · SDK · REST+SSE consumer   │       │  Android/iOS/browser/SIP phone      │
   │  identity = AI key / service token      │       │  identity = phone token (short-lived)│
   └─────────────────────┬───────────────────┘       └───────────────────┬─────────────────┘
                         │ TLS (mTLS optional, prod)                     │ TLS / DTLS-SRTP
 ────────────────────────┼───────────────────────────────────────────────┼──────────────────────
                         ▼                                               ▼
   ┌──────────────────────────────────────────────────────────────────────────────────────────┐
   │                        API Gateway / Worker (Node)  [zone C]                              │
   │  authn/authz · idempotency · rate limits · input validation (zod) · audit · outbox        │
   └───────┬────────────────────────────────┬───────────────────────────────┬──────────────────┘
           │ structured secrets             │ provider adapters             │ Postgres
           ▼                                ▼                               ▼
   ┌────────────────┐            ┌────────────────────┐          ┌────────────────────────────┐
   │ Env / KM/secret │            │ STT/TTS/transport  │          │ event_log (truth) · calls ·│
   │ manager (zone D)│            │ vendors (zone E)   │          │ transcript · audit (zone C′)│
   └────────────────┘            └────────────────────┘          └────────────────────────────┘
```

- **Zone A→C**: AI identity must *only* act on calls it owns (v1 `authorizeCall`/`checkCallOwnership`
  preserved). A compromised vendor key (E) must not yield a session or call credential.
- **Zone B→C**: devices may only publish speech/DMFT and receive their own call's projections —
  **never** `function_call.*` arguments/output, tool event payloads, or other users' data
  (subscription scopes in [03-event-model.md](./03-event-model.md) §4).
- **Zone C′**: DB credentials are least-privilege (app role: CRUD on app tables; no DDL; no
  `pg_read_server_files`); the migration/backfill runs under a separate migration role.

---

## 3. Authentication (token classes)

| Class | Credential | Storage | Lifetime | Notes (v1→v2) |
|-------|-----------|---------|----------|----------------|
| Service | `SERVICE_TOKEN` env | env/KM, never code | long; rotated | v1 default identity "AI Agent"; dev-token refusal in production (existing `dev-token-guard`) — keep **MUST** |
| AI key | random 256-bit, `key_hash` SHA-256 stored only | DB `ai_keys` | revocable; v2 adds `expires_at`, scopes, `max_calls` | plaintext returned exactly once at mint (v1 pattern) **MUST preserve** |
| Device | `phone/token` — short-lived, minted ≤10/min per user (v1 rate limit) | DB `phone_tokens` | short (existing expiry) | rate limit **MUST** survive the /v2 upgrade |
| Ops/dashboard | `api_keys` (service-level) | DB, hashed | long; rotated | new v2 table; scopes `{service:ro}` default |

- Keys are hashed at rest; **no reversible storage**. Logs must never echo tokens/keys
  (`logger.redact` / pino-redact keys like `token`, `*_key`, `Authorization`). **MUST**
- Byte-compare tokens in constant time to avoid timing attacks. **MUST**
- Full rotation workflow per class (rotate on leak; `ai_keys.last_used_at` check for stale
  keys; force re-mint on compromise). **SHOULD**

---

## 4. Authorization

- Ownership: per-call `agent_id` gate on *every* command and subscription (v1
  `authorizeCall` carried to all v2 endpoints). **MUST**
- Scopes: subscription token carries `scope: "ai:call" | "device:call" | "ops"`. The event
  stream filters by scope server-side — a device token *cannot* request AI-scoped events by
  changing query params. **MUST**
- Service token may act across identities only in explicit admin operations (audited). **SHOULD**
- Transfer changes `agent_id` → re-authorize under the new owner; the previous owner's
  subscriptions are dropped on `call.transfer.completed`. **MUST**

---

## 5. Transport & media security

- All REST/SSE/WS: **TLS 1.2+** (Caddy terminates in the reference deployment; HSTS, secure
  cookies/TLS termination). WS events + media: `wss://`.
- WebRTC audio: **DTLS-SRTP** (mandatory DTLS; no unencrypted RTP). TURN (coturn) uses
  auth credential-time-limited tokens; no plaintext `static-auth-secret` leak into clients.
- SIP (future): TLS/SRTP only; otherwise refuse to attach.
- **Recordings at rest:** envelope encryption — data key per recording (AES-256-GCM), wrapped
  by a KMS/HSM key; only `key_id` refs stored (schema `recordings.key_id`, never the key). **MUST**

---

## 6. Input validation & injection (AWS-request-of-input)

- Zod at every trust boundary (project rule). Sanitize **STT text** before it is sent into
  prompts/transcripts/audio TTS injection (see injection below). **MUST**
- Tool invocation `args` are validated JSONB; tool-name allow-list (no arbitrary module
  invocation). **MUST**
- **SSRF guard on tool execution:** tool backends (calendar, db, browser, search) may make
  outbound HTTP — cap redirects, reject private-loopback ranges unless explicitly allowed,
  time-bound requests. **MUST**
- Transcript/segments: untrusted user text stored and searchable — treat as attacker-controlled
  on read (a malicious caller could script `function_call` prompts). **MUST**

---

## 7. Event stream security

| Threat | Control |
|--------|---------|
| **Prompt/audio injection** — human says instructions embedded in speech that reach the AI's prompt | AI side owns prompt isolation; platform passes `actor.type` and flags `speech.final` from devices distinctly; never echo `function_call` output to devices; documents a **prompt-injection guidance** contract for AI integrators |
| Replay of old events to spoof state | consumers validate `occurred_at`, resubscribe only from their `Last-Event-ID`; `sequence` gaps detected and resynced |
| Confidential event leakage to devices | scope filter (§4) enforced server-side, not client-side |
| Event-log tampering (postgres row edit) | immutable append-only write paths; audit log separate-append; nightly hash-chain/verification job **SHOULD** |

---

## 8. Replay/protection & rate limiting (from live v1, extended)

- Idempotency: `Idempotency-Key` first-response cache (24 h) + `client_message_id` for
  user-text; both map to the same store ([04-api-spec.md](./04-api-spec.md) §1). **MUST**
- Rate limits (§4 in API spec) per identity AND per call; `/phone/token` stays capped
  10/min (existing); event connects 10/min/call; media frames 50 msg/s/conn. 429s carry
  `Retry-After`.
- Per-identity concurrent-call caps (v2 `max_calls`) — prevent quota-abuse storms. **SHOULD**

---

## 9. Audit logging

`audit_log` is append-only ([05-database-schema.md](./05-database-schema.md) §2.10): actor,
action, resource, ip, `before`/`after` JSONB. Mandatory audited actions: key mint/revoke,
phone-token mint, call create/hangup/transfer/pause, tool invocations, retention deletion,
config changes. Audit rows are written in the same transaction as the mutation where possible
(outbox discipline), so an event without an audit trail signals a bug. **MUST**

---

## 10. Secrets management

- Env-only for runtime secrets (`.env` documented in `.env.example`, never committed —
  `.gitignore` enforced); KMS/secret manager for scale. **MUST**
- The v2 design adds **no** credentials to code; provider keys (Deepgram/ElevenLabs/etc.)
  are per-call `provider_config` references, resolved from the secret store at provider
  attach — never persisted in `events.payload`. **MUST**
- `.env` / key rotation playbook: rotate quarterly or on incident; maintain break-glass
  credentials under dual control. **SHOULD**

---

## 11. Compliance & privacy

- **PCI/PII:** recordings and transcripts contain speech data — treat as sensitive; retention
  per `calls.retention_expires_at` (default 30 days, configurable); right-to-erasure =
  delete `calls`/`transcript_segments` + archive recordings per policy.
- **HIPAA-style (PHI)**: if clinical use appears, see `docs` compliance checks (encrypted
  recordings, scoped access, audit) — flagged as an explicit product decision, not assumed.
- **GDPR/CCPA:** data-subject export (transcript + recording) and delete endpoints under ops
  scope. **SHOULD**

---

## 12. Verification & review gates

| Check | When | Tool |
|-------|------|------|
| Dependency vulns | CI every PR | `npm audit` / OSV |
| Secret scan (pre-commit) | commit | hooks from ECC/secret scanner; fail on `.env` |
| Pen-test the authz boundaries | each Phase in migration | security-review checklist + contract suite |
| Fuzzer for MCP/WS inputs | CI nightly | zod strict + fuzz harness |
| Production hardening scan | pre-release | helmet/csp headers, TLS config, headers audit (v1 already uses helmet) |
| Recordings encryption at rest test | restore drills | backup verification job |

**Explicit non-goals (out of scope of v2 v1.0):** BYO-KMS vendor integrations, mTLS client
certs for every AI consumer, and per-tenant CRLs. These are documented as NICE and tracked in
[10-roadmap.md](./10-roadmap.md) §4.