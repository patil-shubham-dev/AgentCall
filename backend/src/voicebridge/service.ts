import crypto from 'node:crypto';
import { WebSocket } from 'ws';
import { logger } from '../common/logger.js';
import type {
  VoiceCallSession,
  VoiceMessage,
  CreateCallInput,
  CallStatus,
  EnrichedMessage,
  BargeInResult,
  CallbackRequest,
} from './types.js';
import { enrichText, detectBargeIn, emotionOf } from './types.js';

const sessions = new Map<string, VoiceCallSession>();
const phoneConnections = new Map<string, WebSocket>();
const scheduledCallbacks = new Map<string, { callId: string; resumeAt: number }>();

function newId(): string {
  return crypto.randomUUID();
}

function now(): string {
  return new Date().toISOString();
}

export function createCall(input: CreateCallInput): VoiceCallSession {
  const session: VoiceCallSession = {
    id: newId(),
    userId: input.userId,
    agentId: input.agentId,
    status: 'pending',
    priority: input.priority ?? 'normal',
    reason: input.reason,
    context: {
      taskId: input.taskId,
      summary: input.summary,
      options: input.options,
    },
    messages: [
      {
        id: newId(),
        role: 'system',
        type: 'text',
        content: `Call initiated: ${input.reason} - ${input.summary}`,
        createdAt: now(),
      },
    ],
    createdAt: now(),
  };

  sessions.set(session.id, session);
  logger.info({ callId: session.id, reason: input.reason }, 'Call session created');

  notifyPhone(session.userId, {
    type: 'call_incoming',
    callId: session.id,
    reason: input.reason,
    summary: input.summary,
    options: input.options,
    priority: input.priority,
  });

  return session;
}

export function getCall(callId: string): VoiceCallSession | undefined {
  return sessions.get(callId);
}

export function getUserActiveCall(userId: string): VoiceCallSession | undefined {
  for (const session of sessions.values()) {
    if (session.userId === userId && (session.status === 'pending' || session.status === 'active')) {
      return session;
    }
  }
  return undefined;
}

export function addMessage(
  callId: string,
  role: 'ai' | 'user',
  content: string,
  type: 'text' | 'audio' = 'text',
  enriched?: EnrichedMessage,
): VoiceMessage | undefined {
  const session = sessions.get(callId);
  if (!session) return undefined;

  const msg: VoiceMessage = {
    id: newId(),
    role,
    type,
    content,
    enriched,
    createdAt: now(),
  };

  session.messages.push(msg);

  if (role === 'ai') {
    if (session.status === 'pending') {
      session.status = 'active';
      session.connectedAt = now();
    }
    notifyPhone(session.userId, {
      type: 'ai_message',
      callId,
      message: msg,
      enriched: enriched ?? enrichText(content),
    });
  }

  logger.info({ callId, role, type, enriched: !!enriched }, 'Message added to session');
  return msg;
}

export function addAiMessage(callId: string, rawText: string): VoiceMessage | undefined {
  const enriched = enrichText(rawText);
  return addMessage(callId, 'ai', enriched.cleanText, 'text', enriched);
}

export function processTextMessage(
  callId: string,
  text: string,
): { text: string; bargeIn: BargeInResult } {
  const session = sessions.get(callId);
  if (!session) throw new Error(`Call session not found: ${callId}`);

  logger.info({ callId, text: text.slice(0, 100) }, 'Processing user text message');

  const bargeIn = detectBargeIn(text);
  addMessage(callId, 'user', text, 'text');

  if (bargeIn.detected) {
    notifyPhone(session.userId, {
      type: 'barge_in_detected',
      callId,
      action: bargeIn.action,
      callbackMinutes: bargeIn.callbackMinutes,
    });
  }

  logger.info({ callId, bargeIn: bargeIn.action }, 'Text message processed');
  return { text, bargeIn };
}

export function scheduleCallback(params: CallbackRequest): boolean {
  const session = sessions.get(params.callId);
  if (!session) return false;

  const resumeAt = Date.now() + params.delayMinutes * 60 * 1000;
  session.status = 'paused';
  scheduledCallbacks.set(session.userId, { callId: params.callId, resumeAt });

  notifyPhone(session.userId, {
    type: 'callback_scheduled',
    callId: params.callId,
    delayMinutes: params.delayMinutes,
    resumeAt: new Date(resumeAt).toISOString(),
  });

  const handle = setTimeout(() => {
    const existing = sessions.get(params.callId);
    if (existing && existing.status === 'paused') {
      existing.status = 'pending';
      notifyPhone(session.userId, {
        type: 'call_incoming',
        callId: params.callId,
        reason: existing.reason,
        summary: existing.context.summary,
        options: existing.context.options,
        priority: existing.priority,
        isCallback: true,
      });
      scheduledCallbacks.delete(session.userId);
    }
  }, params.delayMinutes * 60 * 1000);
  handle.unref();

  logger.info({ callId: params.callId, delayMinutes: params.delayMinutes }, 'Callback scheduled');
  return true;
}

export function completeCall(
  callId: string,
  result?: { transcriptSummary?: string; decision?: string; selectedOption?: string; sentiment?: string; actionItems?: string[] },
): VoiceCallSession | undefined {
  const session = sessions.get(callId);
  if (!session) return undefined;

  session.status = 'completed';
  session.completedAt = now();

  if (result) {
    session.result = result;
  }

  if (!session.result) {
    const userMessages = session.messages.filter((m) => m.role === 'user');
    session.result = {
      transcriptSummary: userMessages.map((m) => m.content).join('\n'),
      userResponse: userMessages[userMessages.length - 1]?.content,
    };
  }

  scheduledCallbacks.delete(session.userId);
  notifyPhone(session.userId, { type: 'call_ended', callId });
  logger.info({ callId }, 'Call session completed');
  return session;
}

export function cancelCall(callId: string): VoiceCallSession | undefined {
  const session = sessions.get(callId);
  if (!session) return undefined;

  session.status = 'cancelled';
  session.completedAt = now();

  scheduledCallbacks.delete(session.userId);
  notifyPhone(session.userId, { type: 'call_cancelled', callId });
  logger.info({ callId }, 'Call session cancelled');
  return session;
}

export function getTranscript(callId: string): VoiceMessage[] | undefined {
  const session = sessions.get(callId);
  if (!session) return undefined;
  return session.messages;
}

export function registerPhone(userId: string, ws: WebSocket): void {
  const existing = phoneConnections.get(userId);
  if (existing && existing.readyState === WebSocket.OPEN) {
    existing.close(1000, 'New connection replacing');
  }
  phoneConnections.set(userId, ws);

  ws.on('close', () => {
    if (phoneConnections.get(userId) === ws) {
      phoneConnections.delete(userId);
    }
  });

  logger.info({ userId }, 'Phone registered via WebSocket');
}

export function notifyPhone(userId: string, payload: Record<string, unknown>): boolean {
  const ws = phoneConnections.get(userId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify(payload));
    return true;
  }
  logger.warn({ userId }, 'Phone not connected, message queued (not delivered)');
  return false;
}

export function getSessions(): VoiceCallSession[] {
  return Array.from(sessions.values());
}
