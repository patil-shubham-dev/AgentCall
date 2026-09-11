import { GoogleAuth } from 'google-auth-library';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';
import { getFcmToken, removeFcmToken } from './fcm-tokens.js';

/**
 * FCM (Firebase Cloud Messaging) ring delivery — Phase A, additive only.
 *
 * Design constraints:
 * - LAZY: nothing is minted or sent unless FCM_ENABLED=true AND a real
 *   call_incoming ring is being pushed. Disabled is a fully silent no-op —
 *   no tokens minted, no HTTP, no log lines per ring.
 * - FIRE-AND-FORGET from the caller's perspective: sendFcmPush returns a
 *   promise the ring path never awaits. FCM is a second delivery attempt
 *   alongside the existing WS/queue path; it must never delay or alter the
 *   existing path's return value.
 * - Failure semantics: 404 / UNREGISTERED means the token is dead (app
 *   reinstalled, token rotated) → drop it so we never push a dead token again.
 *   5xx (or any transport error) means the server was unreachable → keep the
 *   token; the next ring retries.
 */

const FCM_SEND_URL = (projectId: string) =>
  `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`;

// Firebase message data values must be strings — the app parses the JSON-encoded
// fields back out. Kept explicit so the push payload mirrors the WS payload
// (which carries real types) exactly.
function toDataPayload(payload: Record<string, unknown>): Record<string, string> {
  const data: Record<string, string> = {};
  for (const [key, value] of Object.entries(payload)) {
    if (value === undefined || value === null) continue;
    data[key] = typeof value === 'string' ? value : JSON.stringify(value);
  }
  return data;
}

export interface FcmSendResult {
  ok: boolean;
  /** true when the token was deleted because FCM rejected it as dead. */
  tokenRemoved: boolean;
  error?: string;
  /** Google FCM message name on success: projects/<id>/messages/<msgId> — for send↔receipt correlation. */
  fcmMessageId?: string;
}

let auth: GoogleAuth | undefined;

/** Lazily build the GoogleAuth instance once credentials are configured. */
function getAuth(): GoogleAuth | undefined {
  if (auth) return auth;
  const { serviceAccountPath, projectId } = config.fcm;
  if (!serviceAccountPath || !projectId) {
    logger.warn(
      '[fcm] FCM_ENABLED=true but FIREBASE_SERVICE_ACCOUNT_PATH/FCM_PROJECT_ID missing — ring push disabled',
    );
    return undefined;
  }
  // Path-only log (never key material): on Render this should be the
  // /etc/secrets mount resolved at config load; a relative path here means
  // pushes will fail per-ring, so make the effective value visible at init.
  logger.info({ serviceAccountPath, projectId }, '[fcm] initializing GoogleAuth for push');
  auth = new GoogleAuth({
    keyFile: serviceAccountPath,
    scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
  });
  return auth;
}

/**
 * Push a ring to the user's registered device. Always called with the SAME
 * payload the WS path delivers, so the phone can dedupe against a WS/poll
 * ring and against repeat pushes (its recentlyRung guard). Returns a result
 * object for tests; the ring path calls `void sendFcmPush(...)`.
 */
export async function sendFcmPush(userId: string, payload: Record<string, unknown>): Promise<FcmSendResult> {
  if (!config.fcm.enabled) {
    // Silent no-op — deliberately no log line, so a dark merge never spams.
    return { ok: false, tokenRemoved: false, error: 'fcm-disabled' };
  }
  const token = await getFcmToken(userId);
  if (!token) {
    logger.debug({ userId }, '[fcm] no registered token, skipping push');
    return { ok: false, tokenRemoved: false, error: 'no-token' };
  }
  const authInstance = getAuth();
  if (!authInstance) {
    return { ok: false, tokenRemoved: false, error: 'not-configured' };
  }
  try {
    const diagCallId = (payload.callId as string) || (payload.call_id as string) || 'unknown';
    const diagFcmStartMs = Date.now();
    logger.info({ userId, callId: diagCallId, msgType: payload.type }, '[diag:fcm_send_start] sending FCM');
    const accessToken = await authInstance.getAccessToken();
    if (!accessToken) {
      logger.error({ userId, callId: diagCallId }, '[fcm] failed to mint access token');
      logger.warn({ userId, callId: diagCallId, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] token-mint-failed');
      return { ok: false, tokenRemoved: false, error: 'token-mint-failed' };
    }
    const body = {
      message: {
        token,
        data: toDataPayload(payload),
        android: { priority: 'high' },
      },
    };
    const response = await fetch(FCM_SEND_URL(config.fcm.projectId), {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    });
    if (response.status === 404 || response.status === 410) {
      // Token no longer valid (UNREGISTERED is a 404 in HTTP v1). Drop it so
      // the next ring doesn't waste a send on a dead token.
      await removeFcmToken(token);
      logger.warn({ userId, callId: diagCallId, status: response.status, elapsedMs: Date.now() - diagFcmStartMs }, '[fcm] token rejected as dead, removed');
      logger.warn({ userId, callId: diagCallId, status: response.status, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] dead token removed');
      return { ok: false, tokenRemoved: true, error: `fcm-${response.status}` };
    }
    if (response.status === 400) {
      // Live-API reality (probed 2026-08-18): a token that is not registered
      // with this sender returns 400 INVALID_ARGUMENT with "The registration
      // token is not a valid FCM registration token" — NOT the documented 404
      // UNREGISTERED (that only fires for genuine-format revoked tokens). The
      // 400 is permanent, so a dead token must be dropped, not kept as
      // transient. Other 400s (malformed payload) are a code bug — keep the
      // token so the next ring still fires.
      const text = await response.text().catch(() => '');
      if (/registration token/i.test(text) && /not a valid/i.test(text)) {
        await removeFcmToken(token);
        logger.warn({ userId, callId: diagCallId, status: 400, elapsedMs: Date.now() - diagFcmStartMs }, '[fcm] token rejected as dead (400 INVALID_ARGUMENT), removed');
        logger.warn({ userId, callId: diagCallId, status: 400, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] dead token 400 removed');
        return { ok: false, tokenRemoved: true, error: 'fcm-400-invalid-token' };
      }
      logger.warn({ userId, callId: diagCallId, status: 400, text: text.slice(0, 200), elapsedMs: Date.now() - diagFcmStartMs }, '[fcm] send failed (payload error, kept token)');
      logger.warn({ userId, callId: diagCallId, status: 400, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] 400 payload error');
      return { ok: false, tokenRemoved: false, error: 'fcm-400' };
    }
    if (!response.ok) {
      // 5xx / 429 etc — server-side or transient. Keep the token; next ring retries.
      const text = await response.text().catch(() => '');
      logger.warn({ userId, callId: diagCallId, status: response.status, text: text.slice(0, 200), elapsedMs: Date.now() - diagFcmStartMs }, '[fcm] send failed (transient)');
      logger.warn({ userId, callId: diagCallId, status: response.status, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] transient failure');
      return { ok: false, tokenRemoved: false, error: `fcm-${response.status}` };
    }
    // Google FCM v1 returns {name: "projects/<id>/messages/<msgId>"} on success — this is the correlation ID.
    let fcmMessageId: string | undefined;
    try {
      const json = (await response.clone().json()) as { name?: string };
      fcmMessageId = json?.name;
    } catch {
      // ignore parse failure; still a success
    }
    logger.info({ userId, callId: diagCallId, msgType: payload.type, fcmMessageId, elapsedMs: Date.now() - diagFcmStartMs }, '[fcm] ring push delivered');
    logger.info({ userId, callId: diagCallId, fcmMessageId, elapsedMs: Date.now() - diagFcmStartMs }, '[diag:fcm_response] ok');
    return { ok: true, tokenRemoved: false, fcmMessageId };
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err);
    logger.warn({ userId, err: message }, '[fcm] transport error (kept token, retry next ring)');
    return { ok: false, tokenRemoved: false, error: 'transport' };
  }
}
