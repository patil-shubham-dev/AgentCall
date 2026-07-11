import { WebSocketServer, WebSocket } from 'ws';
import { IncomingMessage } from 'node:http';
import { verifyAccessToken, isJWTBlacklisted } from '../auth/service.js';
import { addParticipant, removeParticipant, getCall } from '../call/service.js';
import { redis } from '../db/redis.js';
import { logger } from '../common/logger.js';
import { config } from '../common/config.js';
import type { SignalMessage } from '../common/types.js';

// ──────────────────────────────────────────────
// Types
// ──────────────────────────────────────────────

interface ClientSession {
  ws: WebSocket;
  userId: string;
  callId: string;
  deviceId?: string;
  role: 'caller' | 'callee' | 'observer';
}

interface RateLimitState {
  /** Token bucket: remaining tokens */
  tokens: number;
  /** When the tokens were last refilled (ms since epoch) */
  lastRefill: number;
}

interface ConnectionRateState {
  /** Count of connections in the current window */
  count: number;
  /** Window start time (ms since epoch) */
  windowStart: number;
}

// ──────────────────────────────────────────────
// Rate limiting constants
// ──────────────────────────────────────────────

const MAX_MESSAGE_SIZE = config.signaling.maxMessageSize; // default 256 KB
const RATE_LIMIT_MESSAGES = config.signaling.rateLimitMessages; // default 30
const RATE_LIMIT_WINDOW_MS = config.signaling.rateLimitWindowSec * 1000; // default 10 seconds
const CONNECTION_RATE_LIMIT = config.signaling.connectionRateLimit; // default 5 connections/sec
const CONNECTION_RATE_WINDOW_MS = 1000;

// ──────────────────────────────────────────────
// State
// ──────────────────────────────────────────────

const rooms = new Map<string, Map<string, ClientSession>>();

/** Per-WebSocket rate limit state (keyed by a random client token) */
const clientRateLimits = new Map<WebSocket, RateLimitState>();

/** Per-IP connection rate limiting */
const connectionRateLimits = new Map<string, ConnectionRateState>();

// ──────────────────────────────────────────────
// Rate limit helpers
// ──────────────────────────────────────────────

/**
 * Token bucket rate limiter for WebSocket messages.
 * Each client has a bucket of N tokens that refills every window.
 * One message = one token. If the bucket is empty, the message is rejected.
 */
function checkMessageRateLimit(ws: WebSocket): boolean {
  const now = Date.now();
  let state = clientRateLimits.get(ws);

  if (!state) {
    state = { tokens: RATE_LIMIT_MESSAGES, lastRefill: now };
    clientRateLimits.set(ws, state);
  }

  // Refill tokens based on elapsed time
  const elapsed = now - state.lastRefill;
  if (elapsed >= RATE_LIMIT_WINDOW_MS) {
    const refillCount = Math.floor(elapsed / RATE_LIMIT_WINDOW_MS) * RATE_LIMIT_MESSAGES;
    state.tokens = Math.min(RATE_LIMIT_MESSAGES, state.tokens + refillCount);
    state.lastRefill = now;
  }

  if (state.tokens <= 0) {
    return false; // Rate limited
  }

  state.tokens--;
  return true;
}

/**
 * Per-IP connection rate limiter — max N new connections per second.
 */
function checkConnectionRateLimit(ip: string): boolean {
  const now = Date.now();
  let state = connectionRateLimits.get(ip);

  if (!state || now - state.windowStart >= CONNECTION_RATE_WINDOW_MS) {
    state = { count: 0, windowStart: now };
    connectionRateLimits.set(ip, state);
  }

  state.count++;
  return state.count <= CONNECTION_RATE_LIMIT;
}

function cleanupClientRateLimit(ws: WebSocket): void {
  clientRateLimits.delete(ws);
}

/**
 * Periodically evict stale rate limit entries to prevent memory leaks
 * from disconnected clients that weren't cleaned up properly.
 */
function startRateLimitEviction(): NodeJS.Timeout {
  return setInterval(() => {
    const now = Date.now();
    for (const [ws, state] of clientRateLimits) {
      if (ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING) {
        clientRateLimits.delete(ws);
      }
    }
    // Evict IP entries older than 10 seconds
    for (const [ip, state] of connectionRateLimits) {
      if (now - state.windowStart > CONNECTION_RATE_WINDOW_MS * 10) {
        connectionRateLimits.delete(ip);
      }
    }
  }, 30_000).unref();
}

// ──────────────────────────────────────────────
// Room management
// ──────────────────────────────────────────────

function getRoom(callId: string): Map<string, ClientSession> {
  if (!rooms.has(callId)) {
    rooms.set(callId, new Map());
  }
  return rooms.get(callId)!;
}

function broadcast(room: Map<string, ClientSession>, message: SignalMessage, excludeUserId?: string): void {
  const data = JSON.stringify(message);
  for (const [userId, session] of room) {
    if (userId !== excludeUserId && session.ws.readyState === WebSocket.OPEN) {
      session.ws.send(data);
    }
  }
}

// ──────────────────────────────────────────────
// Authentication
// ──────────────────────────────────────────────

async function authenticate(ws: WebSocket, req: IncomingMessage): Promise<{ userId: string; callId: string } | null> {
  const url = new URL(req.url ?? '', 'http://localhost');
  const token = url.searchParams.get('token');
  const callId = url.searchParams.get('call_id');

  if (!token || !callId) {
    ws.close(4001, 'Missing token or call_id');
    return null;
  }

  try {
    const payload = verifyAccessToken(token);
    const blacklisted = await isJWTBlacklisted(payload.jti);
    if (blacklisted) {
      ws.close(4001, 'Token revoked');
      return null;
    }

    return { userId: payload.sub, callId };
  } catch {
    ws.close(4001, 'Invalid token');
    return null;
  }
}

// ──────────────────────────────────────────────
// Message handling with rate limiting
// ──────────────────────────────────────────────

function handleMessage(session: ClientSession, raw: string): void {
  // 1. Enforce message size limit
  if (raw.length > MAX_MESSAGE_SIZE) {
    session.ws.send(JSON.stringify({
      type: 'error',
      payload: { code: 'MESSAGE_TOO_LARGE', message: `Message exceeds maximum size of ${MAX_MESSAGE_SIZE} bytes` },
      timestamp: new Date().toISOString(),
    }));
    return;
  }

  // 2. Enforce per-client message rate limit
  if (!checkMessageRateLimit(session.ws)) {
    session.ws.send(JSON.stringify({
      type: 'error',
      payload: {
        code: 'RATE_LIMITED',
        message: `Too many messages. Limit: ${RATE_LIMIT_MESSAGES} per ${RATE_LIMIT_WINDOW_MS / 1000}s`,
      },
      timestamp: new Date().toISOString(),
    }));
    return;
  }

  // 3. Parse JSON
  let msg: SignalMessage;
  try {
    msg = JSON.parse(raw) as SignalMessage;
  } catch {
    session.ws.send(JSON.stringify({
      type: 'error',
      payload: { code: 'PARSE_ERROR', message: 'Invalid JSON' },
      timestamp: new Date().toISOString(),
    }));
    return;
  }

  // 4. Route message
  const room = getRoom(session.callId);
  const { type, payload } = msg;

  switch (type) {
    case 'offer':
    case 'answer':
    case 'ice_candidate':
      broadcast(room, msg, session.userId);
      break;

    case 'mute': {
      const muted = payload.muted as boolean;
      broadcast(room, {
        type: 'mute_changed',
        payload: { user_id: session.userId, muted },
        timestamp: new Date().toISOString(),
      });
      break;
    }

    case 'hangup': {
      broadcast(room, {
        type: 'participant_left',
        payload: { user_id: session.userId },
        timestamp: new Date().toISOString(),
      });
      removeParticipant(session.callId, session.userId).catch((err) => logger.error({ err }, 'Failed to remove participant'));
      session.ws.close();
      break;
    }

    default:
      session.ws.send(JSON.stringify({
        type: 'error',
        payload: { code: 'UNKNOWN_TYPE', message: `Unknown message type: ${type}` },
        timestamp: new Date().toISOString(),
      }));
  }
}

// ──────────────────────────────────────────────
// Server creation
// ──────────────────────────────────────────────

export function createSignalingServer(port: number): WebSocketServer {
  const wss = new WebSocketServer({ port });
  const evictionTimer = startRateLimitEviction();

  wss.on('connection', async (ws, req) => {
    // Enforce connection rate limit
    const ip = req.headers['x-forwarded-for'] as string | undefined
      ?? req.socket.remoteAddress
      ?? 'unknown';
    if (!checkConnectionRateLimit(ip)) {
      ws.close(4003, 'Connection rate limited');
      logger.warn({ ip }, 'Connection rate limit exceeded');
      return;
    }

    const auth = await authenticate(ws, req);
    if (!auth) return;

    const { userId, callId } = auth;

    try {
      const call = await getCall(callId);
      const role = call.user_id === userId ? 'callee' : call.agent_id === userId ? 'caller' : 'observer';
      const deviceId = new URL(req.url ?? '', 'http://localhost').searchParams.get('device_id') ?? undefined;

      const session: ClientSession = { ws, userId, callId, deviceId, role };
      const room = getRoom(callId);
      room.set(userId, session);

      await addParticipant(callId, userId, role);

      ws.send(JSON.stringify({
        type: 'room_joined',
        payload: { call_id: callId, participants: Array.from(room.keys()) },
        timestamp: new Date().toISOString(),
      }));

      broadcast(room, {
        type: 'participant_joined',
        payload: { user_id: userId, role },
        timestamp: new Date().toISOString(),
      }, userId);

      logger.info({ userId, callId, role }, 'Client joined signaling room');

      ws.on('message', (data) => {
        handleMessage(session, data.toString());
      });

      ws.on('close', () => {
        room.delete(userId);
        cleanupClientRateLimit(ws);
        broadcast(room, {
          type: 'participant_left',
          payload: { user_id: userId },
          timestamp: new Date().toISOString(),
        });
        removeParticipant(callId, userId).catch((err) => logger.error({ err }, 'Failed to remove participant on close'));

        if (room.size === 0) {
          rooms.delete(callId);
        }

        logger.info({ userId, callId }, 'Client left signaling room');
      });

      ws.on('error', (err) => {
        logger.error({ err, userId, callId }, 'WebSocket error');
        room.delete(userId);
        cleanupClientRateLimit(ws);
      });
    } catch (err) {
      logger.error({ err, userId, callId }, 'Failed to join signaling room');
      ws.close(4004, 'Call not found');
    }
  });

  wss.on('error', (err) => {
    logger.error({ err }, 'Signaling server error');
  });

  wss.on('close', () => {
    clearInterval(evictionTimer);
  });

  logger.info({ port, maxMessageSize: MAX_MESSAGE_SIZE, rateLimit: `${RATE_LIMIT_MESSAGES}/${RATE_LIMIT_WINDOW_MS / 1000}s` }, 'Signaling server started');
  return wss;
}
