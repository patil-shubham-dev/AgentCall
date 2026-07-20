import { WebSocketServer, WebSocket } from 'ws';
import { logger } from '../common/logger.js';
import { config } from '../common/config.js';
import * as voicebridge from '../voicebridge/service.js';

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
    for (const [ws, _state] of clientRateLimits) {
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

export function createSignalingServer(port: number): WebSocketServer {
  const wss = new WebSocketServer({ port });
  const evictionTimer = startEvictionTimer();

  wss.on('connection', (ws, req) => {
    const ip = (req.headers['x-forwarded-for'] as string | undefined) ?? req.socket.remoteAddress ?? 'unknown';
    if (!checkConnectionRateLimit(ip)) {
      ws.close(4003, 'Connection rate limited');
      return;
    }

    const url = new URL(req.url ?? '/', 'http://localhost');
    const path = url.pathname;
    const userId = url.searchParams.get('user_id') ?? 'solo-user';

    if (path === '/phone') {
      voicebridge.registerPhone(userId, ws);
      ws.send(JSON.stringify({
        type: 'connected',
        payload: { user_id: userId, server: 'agentcall-voicebridge' },
        timestamp: new Date().toISOString(),
      }));
      logger.info({ userId }, 'Phone connected to signaling');

      ws.on('message', (data) => {
        if (data.toString().length > MAX_MESSAGE_SIZE) {
          sendError(ws, 'MESSAGE_TOO_LARGE', `Max size: ${MAX_MESSAGE_SIZE} bytes`);
          return;
        }
        if (!checkMessageRateLimit(ws)) {
          sendError(ws, 'RATE_LIMITED', `Limit: ${RATE_LIMIT_MESSAGES}/${RATE_LIMIT_WINDOW_MS / 1000}s`);
          return;
        }
      });
    } else {
      ws.close(4004, 'Unknown path. Use /phone for phone connections.');
    }

    ws.on('close', () => {
      clientRateLimits.delete(ws);
    });

    ws.on('error', (err) => {
      logger.error({ err, userId }, 'WebSocket error');
      clientRateLimits.delete(ws);
    });
  });

  wss.on('error', (err) => {
    logger.error({ err }, 'Signaling server error');
  });

  wss.on('close', () => {
    clearInterval(evictionTimer);
  });

  logger.info({ port }, 'Signaling server started (phone connections only)');
  return wss;
}
