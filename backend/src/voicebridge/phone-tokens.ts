import crypto from 'node:crypto';
import { logger } from '../common/logger.js';

interface PhoneTokenEntry {
  token: string;
  userId: string;
  createdAt: number;
}

const phoneTokens = new Map<string, PhoneTokenEntry>();

const TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 60 * 60 * 1000;

const cleanupTimer = setInterval(() => {
  const now = Date.now();
  for (const [token, entry] of phoneTokens) {
    if (now - entry.createdAt > TOKEN_TTL_MS) {
      phoneTokens.delete(token);
      logger.debug({ userId: entry.userId }, '[phone-tokens] expired token cleaned up');
    }
  }
}, CLEANUP_INTERVAL_MS).unref();

export function createPhoneToken(userId: string): string {
  const token = crypto.randomBytes(32).toString('hex');
  phoneTokens.set(token, { token, userId, createdAt: Date.now() });
  logger.info({ userId }, '[phone-tokens] token created');
  return token;
}

export function validatePhoneToken(token: string): string | null {
  const entry = phoneTokens.get(token);
  if (!entry) return null;
  if (Date.now() - entry.createdAt > TOKEN_TTL_MS) {
    phoneTokens.delete(token);
    return null;
  }
  return entry.userId;
}

export function revokePhoneToken(token: string): void {
  phoneTokens.delete(token);
}
