import { WebSocketServer, WebSocket } from 'ws';
import type { Server } from 'node:http';
import { logger } from '../common/logger.js';
import { config } from '../common/config.js';
import { validatePhoneToken } from '../voicebridge/phone-tokens.js';
import * as voicebridge from '../voicebridge/service.js';
import {
  publishSignalingConnected,
  publishSignalingDisconnected,
  publishSignalingMessageReceived,
  publishSignalingFailed,
} from '../voicebridge/signaling/publisher.js';

interface RateLimitState {
  tokens: number;
  lastRefill: number;
}

const MAX_MESSAGE_SIZE = config.signaling.maxMessageSize;
const RATE_LIMIT_MESSAGES = config.signaling.rateLimitMessages;
const RATE_LIMIT_WINDOW_MS = config.signaling.rateLimitWindowSec * 1000;
const CONNECTION_RATE_LIMIT = config.signaling.connectionRateLimit;
const CONNECTION_RATE_WINDOW_MS = 1000;

const connectionRateLimits = new Map<string, { count: number; windowStart: number }>();
const clientRateLimits = new Map<WebSocket, RateLimitState>();

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

function checkMessageRateLimit(ws: WebSocket): boolean {
  const now = Date.now();
  let state = clientRateLimits.get(ws);
  if (!state) {
    state = { tokens: RATE_LIMIT_MESSAGES, lastRefill: now };
    clientRateLimits.set(ws, state);
  }
  const elapsed = now - state.lastRefill;
  if (elapsed >= RATE_LIMIT_WINDOW_MS) {
    const refillCount = Math.floor(elapsed / RATE_LIMIT_WINDOW_MS) * RATE_LIMIT_MESSAGES;
    state.tokens = Math.min(RATE_LIMIT_MESSAGES, state.tokens + refillCount);
    state.lastRefill = now;
  }
  if (state.tokens <= 0) return false;
  state.tokens--;
  return true;
}

function sendError(ws: WebSocket, code: string, message: string): void {
  ws.send(JSON.stringify({ type: 'error', payload: { code, message }, timestamp: new Date().toISOString() }));
}

function startEvictionTimer(): NodeJS.Timeout {
  return setInterval(() => {
    for (const [ws] of clientRateLimits) {
      if (ws.readyState === WebSocket.CLOSED || ws.readyState === WebSocket.CLOSING) {
        clientRateLimits.delete(ws);
      }
    }
    const now = Date.now();
    for (const [ip, state] of connectionRateLimits) {
      if (now - state.windowStart > CONNECTION_RATE_WINDOW_MS * 10) {
        connectionRateLimits.delete(ip);
      }
    }
  }, 30_000).unref();
}

export function createSignalingServer(server: Server): WebSocketServer {
  const wss = new WebSocketServer({ server, path: '/phone' });
  const evictionTimer = startEvictionTimer();

  wss.on('connection', async (ws, req) => {
    const ip = (req.headers['x-forwarded-for'] as string | undefined) ?? req.socket.remoteAddress ?? 'unknown';
    logger.info({ ip }, '[WS] new connection attempt');
    if (!checkConnectionRateLimit(ip)) {
      logger.warn({ ip }, '[WS] connection rate limited');
      ws.close(4003, 'Connection rate limited');
      return;
    }

    // Authenticate via token query parameter
    const url = new URL(req.url ?? '/', 'http://localhost');
    const token = url.searchParams.get('token');
    const isDev = config.serviceToken === 'dev-service-token';
    if (!isDev) {
      const phoneUserId = token ? await validatePhoneToken(token) : null;
      if (!token || (token !== config.serviceToken && !phoneUserId)) {
        logger.warn({ ip, hasToken: !!token }, '[WS] authentication failed — invalid or missing token');
        ws.close(4001, 'Authentication failed: invalid or missing token');
        return;
      }
    }

    const userId = url.searchParams.get('user_id') ?? 'solo-user';
    logger.info({ userId, ip, path: url.pathname }, '[WS] connection accepted');

    voicebridge.registerPhone(userId, ws);
    ws.send(JSON.stringify({
      type: 'connected',
      payload: { user_id: userId, server: 'agentcall-voicebridge' },
      timestamp: new Date().toISOString(),
    }));
    publishSignalingConnected(userId);
    logger.info({ userId, remoteAddress: ip }, '[WS] phone connected to signaling');

    ws.on('message', (data) => {
      const raw = data.toString();
      const msgSize = raw.length;
      if (msgSize > MAX_MESSAGE_SIZE) {
        logger.warn({ userId, msgSize, maxSize: MAX_MESSAGE_SIZE }, '[WS] message too large');
        publishSignalingFailed(userId, 'message too large');
        sendError(ws, 'MESSAGE_TOO_LARGE', `Max size: ${MAX_MESSAGE_SIZE} bytes`);
        return;
      }
      if (!checkMessageRateLimit(ws)) {
        logger.warn({ userId }, '[WS] message rate limited');
        publishSignalingFailed(userId, 'message rate limited');
        sendError(ws, 'RATE_LIMITED', `Limit: ${RATE_LIMIT_MESSAGES}/${RATE_LIMIT_WINDOW_MS / 1000}s`);
        return;
      }

      let msgType = 'unknown';
      try { msgType = JSON.parse(raw).type ?? 'unknown'; } catch { /* non-JSON message, keep unknown */ }
      publishSignalingMessageReceived(userId, msgType, msgSize);
      logger.info({ userId, msgType, msgSize }, '[WS] <- message from phone');
    });

    ws.on('close', (code, reason) => {
      clientRateLimits.delete(ws);
      publishSignalingDisconnected(userId);
      logger.info({ userId, code, reason: reason.toString() }, '[WS] phone disconnected');
    });

    ws.on('error', (err) => {
      logger.error({ err, userId }, '[WS] phone WebSocket error');
      publishSignalingFailed(userId, 'WebSocket error');
      clientRateLimits.delete(ws);
    });
  });

  wss.on('error', (err) => {
    logger.error({ err }, '[WS] signaling server error');
  });

  wss.on('close', () => {
    clearInterval(evictionTimer);
  });

  logger.info({ path: '/phone' }, 'Signaling server started (phone connections only)');
  return wss;
}
