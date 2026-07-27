import 'dotenv/config';
import { ConfigSchema, type Config } from './schema.js';

function coerceValue(raw: string | undefined, fallback: string): string {
  return raw ?? fallback;
}

function parseIntSafe(raw: string | undefined, defaultVal: string): number {
  const val = parseInt(raw ?? defaultVal, 10);
  if (isNaN(val)) {
    throw new Error(`Invalid config value: expected number, got "${raw ?? defaultVal}"`);
  }
  return val;
}

export function loadConfig(): Config {
  const raw = {
    nodeEnv: coerceValue(process.env['NODE_ENV'], 'development'),
    port: parseIntSafe(process.env['PORT'], '4000'),
    serviceToken: coerceValue(process.env['SERVICE_TOKEN'], ''),
    corsAllowedOrigins: coerceValue(process.env['CORS_ALLOWED_ORIGINS'], '*'),
    bodyLimit: parseIntSafe(process.env['BODY_LIMIT_BYTES'], '1048576'),
    database: {
      url: coerceValue(process.env['DATABASE_URL'], ''),
      poolMin: parseIntSafe(process.env['DB_POOL_MIN'], '2'),
      poolMax: parseIntSafe(process.env['DB_POOL_MAX'], '10'),
      poolAcquireTimeoutMs: parseIntSafe(process.env['DB_POOL_ACQUIRE_TIMEOUT'], '10000'),
      poolIdleTimeoutMs: parseIntSafe(process.env['DB_POOL_IDLE_TIMEOUT'], '30000'),
      verificationIntervalMs: parseIntSafe(process.env['DB_VERIFICATION_INTERVAL_MS'], '0'),
      persistenceMode: coerceValue(process.env['PERSISTENCE_MODE'], 'dual-write'),
    },
    signaling: {
      maxMessageSize: parseIntSafe(process.env['SIGNALING_MAX_MESSAGE_SIZE'], '262144'),
      rateLimitMessages: parseIntSafe(process.env['SIGNALING_RATE_LIMIT_MESSAGES'], '30'),
      rateLimitWindowSec: parseIntSafe(process.env['SIGNALING_RATE_LIMIT_WINDOW'], '10'),
      connectionRateLimit: parseIntSafe(process.env['SIGNALING_CONNECTION_RATE_LIMIT'], '10'),
    },
  };

  const result = ConfigSchema.safeParse(raw);
  if (!result.success) {
    const issues = result.error.issues.map((i) => `${i.path.join('.')}: ${i.message}`);
    throw new Error(`Config validation failed:\n${issues.map((s) => `  - ${s}`).join('\n')}`);
  }

  return deepFreeze(result.data);
}

function deepFreeze<T>(obj: T): T {
  if (obj !== null && typeof obj === 'object' && !Object.isFrozen(obj)) {
    for (const value of Object.values(obj)) {
      deepFreeze(value);
    }
    Object.freeze(obj);
  }
  return obj;
}
