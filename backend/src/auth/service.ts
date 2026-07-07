import crypto from 'node:crypto';
import jwt from 'jsonwebtoken';
import { db } from '../db/connection.js';
import { redis } from '../db/redis.js';
import { config } from '../common/config.js';
import { AppError, UnauthorizedError } from '../common/errors.js';
import { logger } from '../common/logger.js';
import type { Platform } from '../common/types.js';
import { readFileSync } from 'node:fs';

interface TokenPayload {
  sub: string;
  did?: string;
  role: 'user' | 'agent' | 'service';
  iat: number;
  exp: number;
  jti: string;
}

let privateKey: string | undefined;
let publicKey: string | undefined;

function getPrivateKey(): string {
  if (!privateKey) {
    privateKey = readFileSync(config.jwt.privateKeyPath, 'utf-8');
  }
  return privateKey;
}

function getPublicKey(): string {
  if (!publicKey) {
    publicKey = readFileSync(config.jwt.publicKeyPath, 'utf-8');
  }
  return publicKey;
}

function generateJTI(): string {
  return crypto.createHash('sha256').update(crypto.randomBytes(32)).digest('hex');
}

export function signAccessToken(userId: string, deviceId?: string, role: 'user' | 'agent' | 'service' = 'user'): string {
  const payload: Omit<TokenPayload, 'iat' | 'exp' | 'jti'> = {
    sub: userId,
    role,
    ...(deviceId ? { did: deviceId } : {}),
  };

  return jwt.sign(
    { ...payload, jti: generateJTI() },
    getPrivateKey(),
    {
      algorithm: 'RS256',
      expiresIn: config.jwt.accessExpiry as `${number}${'s' | 'm' | 'h' | 'd'}`,
    },
  );
}

export function verifyAccessToken(token: string): TokenPayload {
  try {
    return jwt.verify(token, getPublicKey(), { algorithms: ['RS256'] }) as TokenPayload;
  } catch {
    throw new UnauthorizedError('Invalid or expired access token');
  }
}

export async function createUser(email: string, displayName: string): Promise<string> {
  const [row] = await db('users')
    .insert({ email, display_name: displayName })
    .returning('id');

  return (row as { id: string }).id;
}

export async function findOrCreateUser(email: string, displayName: string): Promise<string> {
  const existing = await db('users').where({ email }).first();
  if (existing) return existing.id;
  return createUser(email, displayName);
}

export async function getDeviceTokens(userId: string): Promise<Array<{ device_id: string; platform: Platform; push_token: string }>> {
  const devices = await db('devices')
    .where({ user_id: userId, is_active: true })
    .whereNotNull('push_token')
    .select('id', 'platform', 'push_token');

  return devices.map((d) => ({
    device_id: d.id,
    platform: d.platform as Platform,
    push_token: d.push_token as string,
  }));
}

export async function registerDevice(
  userId: string,
  platform: Platform,
  pushToken?: string,
  deviceName?: string,
): Promise<string> {
  const [row] = await db('devices')
    .insert({
      user_id: userId,
      platform,
      push_token: pushToken,
      device_name: deviceName,
      push_token_updated_at: pushToken ? new Date() : null,
    })
    .returning('id');

  const deviceId = (row as { id: string }).id;
  logger.info({ userId, deviceId, platform }, 'Device registered');
  return deviceId;
}

export async function removeDevice(deviceId: string): Promise<void> {
  await db('devices').where({ id: deviceId }).update({ is_active: false, push_token: null });
  logger.info({ deviceId }, 'Device removed');
}

export async function storeRefreshToken(userId: string, deviceId: string | null, expiresAt: Date): Promise<string> {
  const token = crypto.randomBytes(64).toString('hex');
  const hash = crypto.createHash('sha256').update(token).digest('hex');

  await db('auth_refresh_tokens').insert({
    user_id: userId,
    token_hash: hash,
    device_id: deviceId,
    expires_at: expiresAt,
  });

  return token;
}

export async function validateRefreshToken(token: string): Promise<string> {
  const hash = crypto.createHash('sha256').update(token).digest('hex');
  const row = await db('auth_refresh_tokens')
    .where({ token_hash: hash, revoked: false })
    .where('expires_at', '>', new Date())
    .first();

  if (!row) throw new UnauthorizedError('Invalid or expired refresh token');

  return (row as { user_id: string }).user_id;
}

export async function revokeRefreshToken(token: string): Promise<void> {
  const hash = crypto.createHash('sha256').update(token).digest('hex');
  await db('auth_refresh_tokens').where({ token_hash: hash }).update({ revoked: true });
}

export async function blacklistJWT(jti: string, expiresAt: Date): Promise<void> {
  await redis.set(`blacklist:${jti}`, '1', 'PXAT', expiresAt.getTime());
}

export async function isJWTBlacklisted(jti: string): Promise<boolean> {
  const val = await redis.get(`blacklist:${jti}`);
  return val !== null;
}

export function generateTURNCredentials(userId: string): { username: string; credential: string; ttl: number } {
  const ttl = 3600;
  const username = `${Math.floor(Date.now() / 1000) + ttl}:${userId}`;
  const credential = crypto
    .createHmac('sha1', config.coturn.secret)
    .update(username)
    .digest('base64');

  return { username, credential, ttl };
}

export async function validateServiceToken(token: string): Promise<boolean> {
  return token === config.serviceToken;
}

export async function createApiKey(userId: string, name: string): Promise<{ api_key: string; name: string; key_prefix: string }> {
  const keyPrefix = `icm_${crypto.randomBytes(4).toString('hex')}`;
  const fullKey = `${keyPrefix}_${crypto.randomBytes(32).toString('hex')}`;
  const keyHash = crypto.createHash('sha256').update(fullKey).digest('hex');

  await db('api_keys').insert({
    user_id: userId,
    name,
    key_prefix: keyPrefix,
    key_hash: keyHash,
  });

  return { api_key: fullKey, name, key_prefix: keyPrefix };
}

export async function listApiKeys(userId: string) {
  return db('api_keys')
    .where({ user_id: userId, is_active: true })
    .select('id', 'name', 'key_prefix', 'permissions', 'last_used_at', 'created_at');
}

export async function revokeApiKey(keyId: string, userId: string): Promise<void> {
  await db('api_keys').where({ id: keyId, user_id: userId }).update({ is_active: false });
}
