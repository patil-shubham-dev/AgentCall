import 'dotenv/config';

function env(name: string, fallback?: string): string {
  return process.env[name] ?? fallback ?? '';
}

function parseIntSafe(raw: string, defaultVal: string): number {
  const val = parseInt(env(raw, defaultVal), 10);
  if (isNaN(val)) {
    throw new Error(`Invalid ${raw}: ${process.env[raw] ?? defaultVal}`);
  }
  return val;
}

export const config = {
  nodeEnv: env('NODE_ENV', 'development'),
  port: parseIntSafe('PORT', '4000'),

  serviceToken: env('SERVICE_TOKEN'),

  database: {
    url: env('DATABASE_URL', ''),
    poolMin: parseIntSafe('DB_POOL_MIN', '2'),
    poolMax: parseIntSafe('DB_POOL_MAX', '10'),
    poolAcquireTimeoutMs: parseIntSafe('DB_POOL_ACQUIRE_TIMEOUT', '10000'),
    poolIdleTimeoutMs: parseIntSafe('DB_POOL_IDLE_TIMEOUT', '30000'),
    verificationIntervalMs: parseIntSafe('DB_VERIFICATION_INTERVAL_MS', '0'),
    persistenceMode: env('PERSISTENCE_MODE', 'dual-write'),
  },

  signaling: {
    maxMessageSize: parseIntSafe('SIGNALING_MAX_MESSAGE_SIZE', '262144'),
    rateLimitMessages: parseIntSafe('SIGNALING_RATE_LIMIT_MESSAGES', '30'),
    rateLimitWindowSec: parseIntSafe('SIGNALING_RATE_LIMIT_WINDOW', '10'),
    connectionRateLimit: parseIntSafe('SIGNALING_CONNECTION_RATE_LIMIT', '10'),
  },

  security: {
    // Fail-closed: empty means no CORS headers are sent, so browsers deny
    // cross-origin requests. Set an explicit comma-separated allowlist.
    corsAllowedOrigins: env('CORS_ALLOWED_ORIGINS', ''),
    bodyLimit: parseIntSafe('BODY_LIMIT_BYTES', '1048576'),
  },

  mcp: {
    // Safety-net wake interval for send_message_and_wait. Replies are now
    // delivered by an in-process session-change event (no poll floor); this
    // only fires if a change bypasses the event bus (e.g. multi-instance run).
    replyPollIntervalMs: parseIntSafe('AI_REPLY_POLL_INTERVAL_MS', '500'),
    // Idle-expiry for MCP sessions: sessions untouched for this long are
    // closed by the periodic sweep. Clients hitting a closed session get
    // SESSION_NOT_FOUND and re-initialize, which is MCP's designed recovery.
    sessionIdleMs: parseIntSafe('MCP_SESSION_IDLE_MS', '1800000'),
    sessionSweepIntervalMs: parseIntSafe('MCP_SESSION_SWEEP_INTERVAL_MS', '60000'),
  },

  // v2 engine (roadmap M1, docs/v2). The v2 REST/SSE namespaces are additive
  // and always mounted; ENGINE_V2 gates the one *behavioral* change to an
  // existing surface — send_message_and_wait losing its 45s server cap and
  // becoming a turn-lease wait (migration plan Phase 1b, roadmap M1 exit).
  v2: {
    // true = MCP send_message_and_wait uses lease semantics: timeout_seconds
    // becomes an optional client window (no maximum); absent = wait until the
    // turn ends (reply / call end / noactivity escalation). false = today's
    // capped behavior, unchanged.
    engineV2: env('ENGINE_V2', 'false') === 'true',
    // Lease-wait safety valve (roadmap risk R7): after this long with no user
    // activity, the wait returns an escalation outcome instead of blocking
    // forever on a silent call. Advisory — never a hard conversation cap.
    noactivityEscalationMs: parseIntSafe('V2_NOACTIVITY_ESCALATION_MS', '300000'),
    // SSE heartbeat interval for GET /api/v2/calls/:id/events.
    sseHeartbeatMs: parseIntSafe('V2_SSE_HEARTBEAT_MS', '15000'),
    // How long a stored idempotency response is replayed (per spec: 24h).
    idempotencyTtlMs: parseIntSafe('V2_IDEMPOTENCY_TTL_MS', '86400000'),
    // Retention (not a conversation cap): calls with no activity for this long
    // are archived by the periodic sweep, mirroring the v1 stale-session
    // sweeper. A live exchange keeps touching lastActivityAt and is never swept.
    callIdleArchiveMs: parseIntSafe('V2_CALL_IDLE_ARCHIVE_MS', '86400000'),
    sweepIntervalMs: parseIntSafe('V2_SWEEP_INTERVAL_MS', '300000'),
  },
} as const;

const VALID_PERSISTENCE_MODES = ['memory', 'dual-write', 'database-read', 'database'] as const;

export type PersistenceMode = (typeof VALID_PERSISTENCE_MODES)[number];

/**
 * Dev-only token that silently grants full service-role access and skips
 * WebSocket auth. Must never be used in production — see
 * assertNoDevTokenInProduction.
 */
export const DEV_SERVICE_TOKEN = 'dev-service-token';

export function assertNoDevTokenInProduction(nodeEnv: string, serviceToken: string): void {
  if (nodeEnv === 'production' && serviceToken === DEV_SERVICE_TOKEN) {
    throw new Error(
      `Refusing to start: SERVICE_TOKEN="${DEV_SERVICE_TOKEN}" is the dev-only token, but NODE_ENV=production. ` +
        'It silently grants full service-role access and skips WebSocket auth. ' +
        'Set a real SERVICE_TOKEN before deploying.',
    );
  }
}

export function validateConfig(): void {
  const required = ['SERVICE_TOKEN'] as const;
  const missing = required.filter((key) => !process.env[key]);
  if (missing.length > 0) {
    throw new Error(`Missing required environment variables: ${missing.join(', ')}`);
  }

  assertNoDevTokenInProduction(config.nodeEnv, config.serviceToken);

  const mode = config.database.persistenceMode as string;
  if (!(VALID_PERSISTENCE_MODES as readonly string[]).includes(mode)) {
    throw new Error(
      `Invalid PERSISTENCE_MODE: "${mode}". Must be one of: ${VALID_PERSISTENCE_MODES.join(', ')}`,
    );
  }
  if ((mode === 'database' || mode === 'database-read') && !config.database.url) {
    throw new Error(`PERSISTENCE_MODE=${mode} requires DATABASE_URL to be set`);
  }
}
