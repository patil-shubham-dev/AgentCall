import { WebSocketServer, WebSocket } from 'ws';
import { IncomingMessage } from 'node:http';
import { verifyAccessToken, isJWTBlacklisted } from '../auth/service.js';
import { addParticipant, removeParticipant, getCall } from '../call/service.js';
import { redis } from '../db/redis.js';
import { logger } from '../common/logger.js';
import type { SignalMessage } from '../common/types.js';

interface ClientSession {
  ws: WebSocket;
  userId: string;
  callId: string;
  deviceId?: string;
  role: 'caller' | 'callee' | 'observer';
}

const rooms = new Map<string, Map<string, ClientSession>>();

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

function handleMessage(session: ClientSession, raw: string): void {
  let msg: SignalMessage;
  try {
    msg = JSON.parse(raw) as SignalMessage;
  } catch {
    session.ws.send(JSON.stringify({ type: 'error', payload: { code: 'PARSE_ERROR', message: 'Invalid JSON' }, timestamp: new Date().toISOString() }));
    return;
  }

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
      broadcast(room, { type: 'mute_changed', payload: { user_id: session.userId, muted }, timestamp: new Date().toISOString() });
      break;
    }

    case 'hangup': {
      broadcast(room, { type: 'participant_left', payload: { user_id: session.userId }, timestamp: new Date().toISOString() });
      removeParticipant(session.callId, session.userId).catch((err) => logger.error({ err }, 'Failed to remove participant'));
      session.ws.close();
      break;
    }

    default:
      session.ws.send(JSON.stringify({ type: 'error', payload: { code: 'UNKNOWN_TYPE', message: `Unknown message type: ${type}` }, timestamp: new Date().toISOString() }));
  }
}

export function createSignalingServer(port: number): WebSocketServer {
  const wss = new WebSocketServer({ port });

  wss.on('connection', async (ws, req) => {
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

      ws.send(JSON.stringify({ type: 'room_joined', payload: { call_id: callId, participants: Array.from(room.keys()) }, timestamp: new Date().toISOString() }));

      broadcast(room, { type: 'participant_joined', payload: { user_id: userId, role }, timestamp: new Date().toISOString() }, userId);

      logger.info({ userId, callId, role }, 'Client joined signaling room');

      ws.on('message', (data) => {
        handleMessage(session, data.toString());
      });

      ws.on('close', () => {
        room.delete(userId);
        broadcast(room, { type: 'participant_left', payload: { user_id: userId }, timestamp: new Date().toISOString() });
        removeParticipant(callId, userId).catch((err) => logger.error({ err }, 'Failed to remove participant on close'));

        if (room.size === 0) {
          rooms.delete(callId);
        }

        logger.info({ userId, callId }, 'Client left signaling room');
      });

      ws.on('error', (err) => {
        logger.error({ err, userId, callId }, 'WebSocket error');
        room.delete(userId);
      });
    } catch (err) {
      logger.error({ err, userId, callId }, 'Failed to join signaling room');
      ws.close(4004, 'Call not found');
    }
  });

  wss.on('error', (err) => {
    logger.error({ err }, 'Signaling server error');
  });

  logger.info({ port }, 'Signaling server started');
  return wss;
}
