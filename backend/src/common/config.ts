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
    corsAllowedOrigins: env('CORS_ALLOWED_ORIGINS', '*'),
    bodyLimit: parseIntSafe('BODY_LIMIT_BYTES', '1048576'),
  },

  mcp: {
    // Safety-net wake interval for send_message_and_wait. Replies are now
    // delivered by an in-process session-change event (no poll floor); this
    // only fires if a change bypasses the event bus (e.g. multi-instance run).
    replyPollIntervalMs: parseIntSafe('AI_REPLY_POLL_INTERVAL_MS', '500'),
  },
} as const;

const VALID_PERSISTENCE_MODES = ['memory', 'dual-write', 'database-read', 'database'] as const;

export type PersistenceMode = (typeof VALID_PERSISTENCE_MODES)[number];

export function validateConfig(): void {
  const required = ['SERVICE_TOKEN'] as const;
  const missing = required.filter((key) => !process.env[key]);
  if (missing.length > 0) {
    throw new Error(`Missing required environment variables: ${missing.join(', ')}`);
  }

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
