import { logger } from './logger.js';

const TRANSIENT_CODES = new Set([
  'ECONNRESET',
  'ETIMEDOUT',
  'ECONNREFUSED',
  'ENOTFOUND',
  'EPIPE',
  '408',
  '57014',
  '40001',
  '40P01',
]);

const TRANSIENT_MESSAGES = [
  'connection reset',
  'connection timeout',
  'timeout',
  'terminating connection',
  'connection refused',
  'deadlock detected',
  'serialization failure',
  'could not connect',
];

function isTransientPostgresError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;

  const code = (err as unknown as Record<string, unknown>).code as string | undefined;
  if (code && TRANSIENT_CODES.has(code)) return true;

  const msg = err.message.toLowerCase();
  for (const pattern of TRANSIENT_MESSAGES) {
    if (msg.includes(pattern)) return true;
  }
  return false;
}

function isValidationError(err: unknown): boolean {
  if (!(err instanceof Error)) return false;
  const code = (err as unknown as Record<string, unknown>).code as string | undefined;
  if (!code) return false;
  return (
    code.startsWith('23') ||
    code.startsWith('42') ||
    code.startsWith('22') ||
    code === '23505' ||
    code === '23503' ||
    code === '23502'
  );
}

export interface RetryOptions {
  maxRetries?: number;
  baseDelayMs?: number;
  maxDelayMs?: number;
}

export async function withRetry<T>(
  fn: () => Promise<T>,
  operationName: string,
  options?: RetryOptions,
): Promise<T> {
  const { maxRetries = 2, baseDelayMs = 50, maxDelayMs = 1000 } = options ?? {};
  let lastError: unknown;

  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      return await fn();
    } catch (err) {
      lastError = err;

      if (isValidationError(err)) {
        throw err;
      }

      if (!isTransientPostgresError(err)) {
        throw err;
      }

      if (attempt < maxRetries) {
        const delay = Math.min(baseDelayMs * Math.pow(2, attempt), maxDelayMs);
        logger.warn(
          { err, operation: operationName, attempt: attempt + 1, maxRetries, delayMs: delay },
          '[Retry] transient error, retrying',
        );
        await new Promise((resolve) => setTimeout(resolve, delay));
      }
    }
  }

  throw lastError;
}
