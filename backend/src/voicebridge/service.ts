import crypto from 'node:crypto';
import { WebSocket } from 'ws';
import { logger } from '../common/logger.js';
import { withSessionLock } from './session-lock.js';
import type {
  VoiceCallSession,
  VoiceMessage,
  CreateCallInput,
  CallbackRequest,
} from './types.js';
import {
  publishNotificationRequested,
  publishNotificationDelivered,
  publishNotificationFailed,
} from './notifications/publisher.js';
import {
  publishPresenceConnected,
  publishPresenceDisconnected,
  publishPresenceUpdated,
} from './presence/publisher.js';
import {
  publishCallCreated,
  publishCallAnswered,
  publishCallPaused,
  publishCallEnded,
  publishCallCancelled,
} from './calls/publisher.js';
import type { SessionRepository, CallbackRepository } from './repositories/index.js';
import type { LifecycleCoordinator } from './lifecycle-coordinator.js';

const COMPLETED_RETENTION_MS = 60 * 60 * 1000;
const CANCELLED_RETENTION_MS = 5 * 60 * 1000;
const STALE_ACTIVE_THRESHOLD_MS = 30 * 60 * 1000;

const phoneConnections = new Map<string, WebSocket>();
const pendingNotifications = new Map<string, Array<{ payload: Record<string, unknown>; timestamp: string }>>();

export function getConnectedPhones(): { userId: string; connected: boolean }[] {
  return Array.from(phoneConnections.entries()).map(([userId, ws]) => ({
    userId,
    connected: ws.readyState === WebSocket.OPEN,
  }));
}

export function getConnectedPhoneCount(): number {
  return phoneConnections.size;
}

function newId(): string {
  return crypto.randomUUID();
}

function now(): string {
  return new Date().toISOString();
}

export class VoiceBridgeService {
  private lifecycleCoordinator: LifecycleCoordinator | null = null;

  constructor(
    private sessionRepo: SessionRepository,
    private callbackRepo: CallbackRepository,
  ) {}

  setLifecycleCoordinator(coordinator: LifecycleCoordinator): void {
    this.lifecycleCoordinator = coordinator;
  }

  async createCall(input: CreateCallInput): Promise<VoiceCallSession> {
    const start = Date.now();
    logger.info({ userId: input.userId, agentId: input.agentId, reason: input.reason }, '[createCall] entered');

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
      lastActivityAt: now(),
    };

    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[createCall] session object built');

    await this.sessionRepo.create(session);
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[createCall] session stored');

    publishCallCreated(session.userId, session.id);

    logger.info({ callId: session.id, userId: session.userId, elapsed: Date.now() - start }, '[createCall] before notifyPhone');
    notifyPhone(session.userId, {
      type: 'call_incoming',
      callId: session.id,
      reason: input.reason,
      summary: input.summary,
      options: input.options,
      priority: input.priority,
    });
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[createCall] after notifyPhone');

    logger.info({ callId: session.id, elapsed: Date.now() - start }, 'Call session created');
    return session;
  }

  async getCall(callId: string): Promise<VoiceCallSession | undefined> {
    return this.sessionRepo.findById(callId);
  }

  async getUserActiveCall(userId: string): Promise<VoiceCallSession | undefined> {
    const userSessions = await this.sessionRepo.findByUserId(userId);
    const cutoff = Date.now() - STALE_ACTIVE_THRESHOLD_MS;
    return userSessions.find((s) => {
      if (s.status !== 'pending' && s.status !== 'active') return false;
      const activityMs = new Date(s.lastActivityAt ?? s.createdAt).getTime();
      return activityMs >= cutoff;
    });
  }

  async answerCall(callId: string): Promise<VoiceCallSession | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      // Idempotent: the phone's persisted answer retries must never re-notify
      // an already-answered or finished call.
      if (session.status === 'active') return session;
      if (session.status === 'completed' || session.status === 'cancelled') return session;

      const wasPaused = session.status === 'paused';
      session.status = 'active';
      session.connectedAt = now();
      // Answering a paused (callback-scheduled) call cancels the pending resume,
      // otherwise the resume timer would re-ring an already-live call.
      if (wasPaused) {
        await this.callbackRepo.delete(session.userId);
      }
      await this.sessionRepo.save(session);
      publishCallAnswered(session.userId, callId);
      notifyPhone(session.userId, { type: 'call_answered', callId });
      logger.info({ callId }, 'Call session answered');
      return session;
    });
  }

  async addMessage(
    callId: string,
    role: 'ai' | 'user',
    content: string,
    type: 'text' | 'audio' = 'text',
  ): Promise<VoiceMessage | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      const msg: VoiceMessage = {
        id: newId(),
        role,
        type,
        content,
        createdAt: now(),
      };

      session.messages.push(msg);
      // Real message traffic (either direction) is the only thing that counts as
      // activity — reconnect pings, active-call polls and notification flushes
      // must never reset this, or abandoned sessions would never expire.
      session.lastActivityAt = msg.createdAt;

      if (role === 'ai') {
        if (session.status === 'pending') {
          session.status = 'active';
          session.connectedAt = now();
          publishCallAnswered(session.userId, callId);
        }
        notifyPhone(session.userId, {
          type: 'ai_message',
          callId,
          message: msg,
        });
      }

      await this.sessionRepo.save(session);
      logger.info({ callId, role, type }, 'Message added to session');
      return msg;
    });
  }

  async addAiMessage(callId: string, content: string): Promise<VoiceMessage | undefined> {
    return this.addMessage(callId, 'ai', content, 'text');
  }

  async processTextMessage(callId: string, text: string): Promise<{ text: string }> {
    const session = await this.sessionRepo.findById(callId);
    if (!session) throw new Error(`Call session not found: ${callId}`);

    logger.info({ callId, text: text.slice(0, 100) }, '[STT] user text received');

    await this.addMessage(callId, 'user', text, 'text');

    logger.info({ callId }, '[STT] text processed');
    return { text };
  }

  async scheduleCallback(params: CallbackRequest): Promise<boolean> {
    return withSessionLock(params.callId, async () => {
      const session = await this.sessionRepo.findById(params.callId);
      if (!session) return false;

      const resumeAt = Date.now() + params.delayMinutes * 60 * 1000;
      session.status = 'paused';
      session.pausedAt = now();
      await this.sessionRepo.save(session);
      await this.callbackRepo.save(session.userId, { callId: params.callId, resumeAt });

      publishCallPaused(session.userId, params.callId, params.delayMinutes, new Date(resumeAt).toISOString());

      notifyPhone(session.userId, {
        type: 'callback_scheduled',
        callId: params.callId,
        delayMinutes: params.delayMinutes,
        resumeAt: new Date(resumeAt).toISOString(),
      });

      this.lifecycleCoordinator?.resumeCallback(session.userId, params.callId, params.delayMinutes, resumeAt);

      logger.info({ callId: params.callId, delayMinutes: params.delayMinutes }, 'Callback scheduled');
      return true;
    });
  }

  async completeCall(
    callId: string,
    result?: { transcriptSummary?: string; decision?: string; selectedOption?: string; sentiment?: string; actionItems?: string[] },
  ): Promise<VoiceCallSession | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      // Idempotent for terminal states: retries from the phone's persisted
      // completion queue must never re-notify or rewrite a finished call.
      if (session.status === 'completed' || session.status === 'cancelled') {
        logger.info({ callId, status: session.status }, 'Call session already terminal, no-op');
        return session;
      }

      session.status = 'completed';
      session.completedAt = now();
      session.retentionExpiresAt = new Date(Date.now() + COMPLETED_RETENTION_MS).toISOString();

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

      await this.sessionRepo.save(session);
      await this.callbackRepo.delete(session.userId);
      publishCallEnded(session.userId, callId);
      notifyPhone(session.userId, { type: 'call_ended', callId });
      logger.info({ callId }, 'Call session completed');
      return session;
    });
  }

  async cancelCall(callId: string): Promise<VoiceCallSession | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      // Idempotent for terminal states: retries from the phone's decline queue
      // must never re-notify the phone or re-publish for an already-finished call.
      if (session.status === 'cancelled') {
        logger.info({ callId }, 'Call session already cancelled, no-op');
        return session;
      }
      if (session.status === 'completed') {
        logger.info({ callId }, 'Call already completed, ignoring cancel');
        return session;
      }

      session.status = 'cancelled';
      session.completedAt = now();
      session.retentionExpiresAt = new Date(Date.now() + CANCELLED_RETENTION_MS).toISOString();

      await this.sessionRepo.save(session);
      await this.callbackRepo.delete(session.userId);
      publishCallCancelled(session.userId, callId);
      notifyPhone(session.userId, { type: 'call_cancelled', callId });
      logger.info({ callId }, 'Call session cancelled');
      return session;
    });
  }

  async getTranscript(callId: string): Promise<VoiceMessage[] | undefined> {
    const session = await this.sessionRepo.findById(callId);
    if (!session) return undefined;
    return session.messages;
  }

  async getSessions(): Promise<VoiceCallSession[]> {
    return this.sessionRepo.list();
  }

  async sweepStaleSessions(): Promise<number> {
    const sessions = await this.sessionRepo.list();
    const nowMs = Date.now();
    const cutoff = nowMs - STALE_ACTIVE_THRESHOLD_MS;
    let completed = 0;

    for (const session of sessions) {
      if (session.status !== 'pending' && session.status !== 'active') continue;
      const activityMs = new Date(session.lastActivityAt ?? session.createdAt).getTime();
      if (activityMs >= cutoff) continue;

      const ageMinutes = Math.max(1, Math.round((nowMs - activityMs) / 60000));
      await this.completeCall(session.id, {
        transcriptSummary: `Auto-completed by stale-session sweep (no conversation activity for ${ageMinutes} min)`,
      });
      logger.info(
        { callId: session.id, userId: session.userId, ageMinutes },
        '[sweep] stale session auto-completed',
      );
      completed++;
    }

    if (completed > 0) {
      logger.info({ completed }, '[sweep] stale-session sweep finished');
    }
    return completed;
  }

  async deleteSession(callId: string): Promise<VoiceCallSession | undefined> {
    return this.sessionRepo.delete(callId);
  }
}

export function registerPhone(userId: string, ws: WebSocket): void {
  const existing = phoneConnections.get(userId);
  if (existing && existing.readyState === WebSocket.OPEN) {
    logger.info({ userId }, '[WS] replacing existing connection');
    existing.close(1000, 'New connection replacing');
  }
  phoneConnections.set(userId, ws);
  logger.info({ userId, activeConnections: phoneConnections.size }, '[WS] phone registered');

  if (existing && existing.readyState === WebSocket.OPEN) {
    publishPresenceUpdated(userId);
  } else {
    publishPresenceConnected(userId);
  }

  ws.on('close', () => {
    if (phoneConnections.get(userId) === ws) {
      phoneConnections.delete(userId);
      logger.info({ userId, activeConnections: phoneConnections.size }, '[WS] phone disconnected');
      publishPresenceDisconnected(userId);
    }
  });

  ws.on('error', (err) => {
    logger.error({ err, userId }, '[WS] phone connection error');
  });

  // Flush any notifications queued while phone was disconnected
  const queued = pendingNotifications.get(userId);
  if (queued && queued.length > 0) {
    pendingNotifications.delete(userId);
    const count = queued.length;
    logger.info({ userId, count }, '[WS] flushing pending notifications');
    for (const { payload } of queued) {
      notifyPhone(userId, payload);
    }
  }
}

export function notifyPhone(userId: string, payload: Record<string, unknown>): boolean {
  const start = Date.now();
  const msgType = (payload.type as string) ?? 'unknown';
  logger.info({ userId, msgType, phoneConnectionsSize: phoneConnections.size, hasConnection: phoneConnections.has(userId) }, '[notifyPhone] entered');

  publishNotificationRequested(userId, msgType, payload);

  const ws = phoneConnections.get(userId);
  if (ws && ws.readyState === WebSocket.OPEN) {
    try {
      ws.send(JSON.stringify({ type: msgType, payload, timestamp: new Date().toISOString() }));
      logger.info({ userId, msgType, elapsed: Date.now() - start }, '[WS] -> sent to phone');
      publishNotificationDelivered(userId, msgType);
      return true;
    } catch (sendErr) {
      const errMsg = sendErr instanceof Error ? sendErr.message : String(sendErr);
      logger.error({ err: sendErr, userId, msgType, elapsed: Date.now() - start }, '[WS] -> send failed');
      publishNotificationFailed(userId, msgType, errMsg);
      return false;
    }
  }
  logger.warn({ userId, msgType, readyState: ws?.readyState, elapsed: Date.now() - start }, '[WS] phone not connected, queuing notification');
  publishNotificationFailed(userId, msgType, 'phone not connected');
  // Queue for delivery when phone reconnects
  const existing = pendingNotifications.get(userId) ?? [];
  existing.push({ payload, timestamp: new Date().toISOString() });
  pendingNotifications.set(userId, existing);
  logger.info({ userId, msgType, queueSize: existing.length }, '[WS] notification queued for later delivery');
  return false;
}

export function isExpired(session: VoiceCallSession, now?: number): boolean {
  if (!session.retentionExpiresAt) return false;
  const nowMs = now ?? Date.now();
  return new Date(session.retentionExpiresAt).getTime() <= nowMs;
}
