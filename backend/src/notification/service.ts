import { readFileSync } from 'node:fs';
import jwt from 'jsonwebtoken';
import { db } from '../db/connection.js';
import { logger } from '../common/logger.js';
import type { Platform } from '../common/types.js';
import { config } from '../common/config.js';

interface PushPayload {
  type: 'call_incoming' | 'task_complete' | 'call_missed';
  call_id?: string;
  task_id?: string;
  caller_name?: string;
  context_summary?: string;
  priority?: string;
  summary?: string;
}

// ──────────────────────────────────────────────
// APNs — JWT Provider Token (ES256)
// ──────────────────────────────────────────────

let apnsJwtCache: { token: string; expiresAt: number } | null = null;
let apnsPrivateKeyContent: string | undefined;

function getAPNsPrivateKey(): string {
  if (!apnsPrivateKeyContent) {
    // Prefer file path, fall back to inline env var
    if (config.apns.privateKeyPath) {
      apnsPrivateKeyContent = readFileSync(config.apns.privateKeyPath, 'utf-8');
    } else if (config.apns.privateKey) {
      apnsPrivateKeyContent = config.apns.privateKey;
    }
  }
  if (!apnsPrivateKeyContent) throw new Error('APNs private key not configured');
  return apnsPrivateKeyContent;
}

/**
 * Generates an ES256 JWT provider token for APNs authentication.
 * The token is valid for up to 1 hour and is cached to reduce signing overhead.
 *
 * JWT structure:
 *   Header:  { "alg": "ES256", "kid": "<KEY_ID>" }
 *   Payload: { "iss": "<TEAM_ID>", "iat": <unix_epoch_seconds> }
 */
function generateAPNsJWT(): string {
  const now = Math.floor(Date.now() / 1000);
  const expiresAt = now + 3300; // 55 minutes (under the 1-hour APNs limit)

  if (apnsJwtCache && apnsJwtCache.expiresAt > Date.now()) {
    return apnsJwtCache.token;
  }

  const privateKey = getAPNsPrivateKey();
  const token = jwt.sign(
    { iss: config.apns.teamId, iat: now },
    privateKey,
    {
      algorithm: 'ES256',
      keyid: config.apns.keyId,
    },
  );

  apnsJwtCache = { token, expiresAt: expiresAt * 1000 };
  return token;
}

// ──────────────────────────────────────────────
// FCM — HTTP v1 API with OAuth2
// ──────────────────────────────────────────────

interface ServiceAccountKey {
  type: string;
  project_id: string;
  private_key_id: string;
  private_key: string;
  client_email: string;
  client_id: string;
  auth_uri: string;
  token_uri: string;
}

let fcmServiceAccount: ServiceAccountKey | null = null;
let fcmAccessTokenCache: { token: string; expiresAt: number } | null = null;

function getFCMServiceAccount(): ServiceAccountKey {
  if (!fcmServiceAccount) {
    if (config.fcm.serviceAccountKeyPath) {
      const raw = readFileSync(config.fcm.serviceAccountKeyPath, 'utf-8');
      fcmServiceAccount = JSON.parse(raw) as ServiceAccountKey;
    } else {
      throw new Error('FCM service account key not configured');
    }
  }
  return fcmServiceAccount;
}

/**
 * Obtains an OAuth2 access token for FCM HTTP v1 API using a
 * Google service account JSON key. The token is cached and reused
 * until it expires (typically 1 hour).
 *
 * Flow:
 *   1. Create a JWT assertion signed with the service account's private key
 *   2. Exchange it for an OAuth2 access token at Google's token endpoint
 *   3. Use the access token as a Bearer token in FCM API requests
 */
async function getFCMAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);

  if (fcmAccessTokenCache && fcmAccessTokenCache.expiresAt > Date.now()) {
    return fcmAccessTokenCache.token;
  }

  const account = getFCMServiceAccount();
  const scope = 'https://www.googleapis.com/auth/firebase.messaging';

  // Step 1: Create JWT assertion
  const assertion = jwt.sign(
    {
      iss: account.client_email,
      scope,
      aud: account.token_uri,
      exp: now + 3600,
      iat: now,
    },
    account.private_key,
    { algorithm: 'RS256' },
  );

  // Step 2: Exchange assertion for access token
  try {
    const response = await fetch(account.token_uri, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
        assertion,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'FCM OAuth2 token exchange failed');
      throw new Error('Failed to obtain FCM access token');
    }

    const data = (await response.json()) as { access_token: string; expires_in: number };
    const expiresAt = now + (data.expires_in - 60); // Buffer 60s before expiry

    fcmAccessTokenCache = { token: data.access_token, expiresAt: expiresAt * 1000 };
    return data.access_token;
  } catch (err) {
    logger.error({ err }, 'FCM OAuth2 token exchange error');
    throw err;
  }
}

async function sendFCMv1(token: string, payload: PushPayload, ttl = 30): Promise<boolean> {
  const account = getFCMServiceAccount();
  const projectId = config.fcm.projectId || account.project_id;

  if (!projectId) {
    logger.warn('FCM project ID not configured, skipping push');
    return false;
  }

  try {
    const accessToken = await getFCMAccessToken();

    const response = await fetch(
      `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
        body: JSON.stringify({
          message: {
            token,
            android: {
              priority: 'high',
              ttl: `${ttl}s`,
              data: payload as unknown as Record<string, string>,
            },
            apns: {
              headers: {
                'apns-priority': '10',
                'apns-expiration': String(Math.floor(Date.now() / 1000) + ttl),
              },
              payload: {
                aps: {
                  alert: {
                    title: payload.caller_name ?? 'AI Call',
                    body: payload.context_summary ?? '',
                  },
                  badge: 1,
                  sound: 'call.caf',
                  'mutable-content': 1,
                },
                ...payload,
              },
            },
          },
        }),
      },
    );

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'FCM v1 send failed');
      return false;
    }

    return true;
  } catch (err) {
    logger.error({ err }, 'FCM v1 send error');
    return false;
  }
}

/**
 * Legacy FCM HTTP API — kept as fallback for projects that haven't
 * migrated to a service account key yet.
 */
async function sendFCMLegacy(token: string, payload: PushPayload, ttl = 30): Promise<boolean> {
  if (!config.fcm.serverKey) {
    logger.warn('FCM legacy not configured, skipping push');
    return false;
  }

  try {
    const response = await fetch('https://fcm.googleapis.com/fcm/send', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `key=${config.fcm.serverKey}`,
      },
      body: JSON.stringify({
        to: token,
        priority: 'high',
        time_to_live: ttl,
        data: payload,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'FCM legacy send failed');
      return false;
    }

    return true;
  } catch (err) {
    logger.error({ err }, 'FCM legacy send error');
    return false;
  }
}

async function sendFCM(token: string, payload: PushPayload, ttl = 30): Promise<boolean> {
  // Prefer HTTP v1 API (OAuth2), fall back to legacy if service account not configured
  if (config.fcm.serviceAccountKeyPath || config.fcm.projectId) {
    return sendFCMv1(token, payload, ttl);
  }
  return sendFCMLegacy(token, payload, ttl);
}

// ──────────────────────────────────────────────
// APNs send
// ──────────────────────────────────────────────

async function sendAPNs(token: string, payload: PushPayload): Promise<boolean> {
  if (!config.apns.keyId || !config.apns.teamId) {
    logger.warn('APNs not configured, skipping push');
    return false;
  }

  try {
    const providerToken = generateAPNsJWT();

    const response = await fetch(`https://api.push.apple.com/3/device/${token}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        Authorization: `bearer ${providerToken}`,
        'apns-topic': 'com.agentcall.app',
        'apns-push-type': 'alert',
        'apns-priority': '10',
      },
      body: JSON.stringify({
        aps: {
          alert: { title: payload.caller_name ?? 'AI Call', body: payload.context_summary ?? '' },
          badge: 1,
          sound: 'call.caf',
          'mutable-content': 1,
        },
        ...payload,
      }),
    });

    if (!response.ok) {
      const body = await response.text();
      logger.error({ status: response.status, body }, 'APNs send failed');
      return false;
    }

    return true;
  } catch (err) {
    logger.error({ err }, 'APNs send error');
    return false;
  }
}

// ──────────────────────────────────────────────
// Public API
// ──────────────────────────────────────────────

export async function sendPush(
  userId: string,
  deviceId: string,
  platform: Platform,
  pushToken: string,
  payload: PushPayload,
): Promise<boolean> {
  let delivered = false;

  if (platform === 'android') {
    delivered = await sendFCM(pushToken, payload);
  } else if (platform === 'ios') {
    delivered = await sendAPNs(pushToken, payload);
  }

  await db('notification_log').insert({
    user_id: userId,
    device_id: deviceId,
    call_id: payload.call_id ?? null,
    notification_type: payload.type,
    status: delivered ? 'delivered' : 'failed',
    provider: platform === 'android' ? 'fcm' : 'apns',
    delivered_at: delivered ? new Date() : null,
  });

  return delivered;
}

export async function sendPushToUser(
  userId: string,
  payload: PushPayload,
): Promise<{ delivered: number; total: number }> {
  const devices = await db('devices')
    .where({ user_id: userId, is_active: true })
    .whereNotNull('push_token')
    .select('id', 'platform', 'push_token');

  if (devices.length === 0) {
    logger.warn({ userId }, 'No push-capable devices found');
    return { delivered: 0, total: 0 };
  }

  let delivered = 0;
  const results = await Promise.allSettled(
    devices.map((d) =>
      sendPush(
        userId,
        d.id,
        d.platform as Platform,
        d.push_token as string,
        payload,
      ),
    ),
  );

  for (const r of results) {
    if (r.status === 'fulfilled' && r.value) delivered++;
  }

  logger.info({ userId, delivered, total: devices.length }, 'Push notification sent');
  return { delivered, total: devices.length };
}

export async function processQueuedNotifications(): Promise<void> {
  const queued = await db('notification_log')
    .where({ status: 'queued' })
    .where('created_at', '>', new Date(Date.now() - 300_000))
    .limit(100);

  for (const n of queued) {
    const userDevice = await db('devices')
      .where({ id: n.device_id, is_active: true })
      .first();

    if (!userDevice || !userDevice.push_token) {
      await db('notification_log').where({ id: n.id }).update({ status: 'failed', error_message: 'No device' });
      continue;
    }

    const payload = JSON.parse(JSON.stringify(n.metadata ?? {})) as PushPayload;
    const delivered = await sendPush(
      n.user_id,
      n.device_id,
      userDevice.platform as Platform,
      userDevice.push_token as string,
      payload,
    );

    await db('notification_log')
      .where({ id: n.id })
      .update({
        status: delivered ? 'delivered' : 'failed',
        delivered_at: delivered ? new Date() : null,
      });
  }
}
