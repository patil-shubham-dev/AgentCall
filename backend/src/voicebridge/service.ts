import crypto from 'node:crypto';
import { WebSocket } from 'ws';
import { logger } from '../common/logger.js';
import { config, DEV_SERVICE_TOKEN } from '../common/config.js';
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
  publishCallAborted,
  publishCallDelayed,
} from './calls/publisher.js';
import type { SessionRepository, CallbackRepository } from './repositories/index.js';
import type { LifecycleCoordinator } from './lifecycle-coordinator.js';
import { sendFcmPush } from './fcm.js';
import type { CleanupScheduler } from '../common/cleanup-scheduler.js';

const COMPLETED_RETENTION_MS = 60 * 60 * 1000;
const CANCELLED_RETENTION_MS = 5 * 60 * 1000;
const STALE_ACTIVE_THRESHOLD_MS = 30 * 60 * 1000;

/** Reason recorded on calls aborted when the owning agent's last MCP session closed. */
export const ABORT_REASON_AGENT_DISCONNECTED = 'agent_disconnected';

/**
 * Notifications queued while the phone was offline are only worth flushing
 * while they are fresh. Anything older is a stale call/event — a long-offline
 * phone must never burst-ring week-old calls on reconnect.
 */
export const QUEUED_NOTIFICATION_TTL_MS = 2 * 60 * 1000;

/**
 * A call_incoming ring is only valid for this long from creation. Deliberately
 * longer than QUEUED_NOTIFICATION_TTL_MS so the phone has time to receive and
 * ring a call that was queued at the last moment.
 */
export const CALL_RING_TTL_MS = 3 * 60 * 1000;

/**
 * Hard ceiling for unanswered 'pending' sessions. Anchored at resumedAt for
 * callback resumes so a resumed call gets a fresh ring window. The activity
 * sweep alone is not enough: AI message traffic and phone polls keep
 * lastActivityAt fresh, so a week-old unanswered call would otherwise stay
 * 'pending' (and ring-able) forever.
 */
export const PENDING_CALL_TTL_MS = 3 * 60 * 1000;

/**
 * Ring-gate retry budget (Phase 2): when the target AI agent is offline the
 * call_incoming push is deferred and re-attempted every RING_RETRY_INTERVAL_MS
 * up to MAX_RING_RETRIES. 12 x 15s = 3 min, matching CALL_RING_TTL_MS /
 * PENDING_CALL_TTL_MS: if the agent never comes back, the pending-TTL sweep
 * cancels the call (a missed call) instead of ringing late.
 */
export const MAX_RING_RETRIES = 12;
export const RING_RETRY_INTERVAL_MS = 15_000;

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

export interface SessionWatcher {
  /** Resolves true when the session changed since the previous call, false on timeout. */
  waitForChange(timeoutMs: number): Promise<boolean>;
  /** Releases the watcher. Must be called when the waiter is done. */
  dispose(): void;
}

export interface AiWaitStatus {
  active: boolean;
  activeUntil: string | null;
  lastActiveAt: string | null;
}

export class VoiceBridgeService {
  private lifecycleCoordinator: LifecycleCoordinator | null = null;
  private readonly sessionChangeCounters = new Map<string, number>();
  private readonly sessionChangeWaiters = new Map<string, Set<() => void>>();
  private readonly aiWaitLeases = new Map<string, { count: number; activeUntil: string | null; lastActiveAt: string }>();

  constructor(
    private sessionRepo: SessionRepository,
    private callbackRepo: CallbackRepository,
  ) {}

  setLifecycleCoordinator(coordinator: LifecycleCoordinator): void {
    this.lifecycleCoordinator = coordinator;
  }

  private ringRetryScheduler: CleanupScheduler | null = null;
  private agentPresenceProvider: (() => Set<string>) | null = null;

  /**
   * Inject the MCP session registry as the agent-presence source (index.ts
   * wires this after registerMcpEndpoint). When no provider is wired the gate
   * is disabled, preserving legacy/test behavior where calls ring always.
   */
  setAgentPresenceProvider(provider: () => Set<string>): void {
    this.agentPresenceProvider = provider;
  }

  /**
   * Inject the timer source for ring-gate retries (index.ts). Without it,
   * deferred rings simply stay pending until the pending-TTL sweep cancels.
   */
  setRingRetryScheduler(scheduler: CleanupScheduler): void {
    this.ringRetryScheduler = scheduler;
  }

  /**
   * Phase-2 gate: is this call's agent online and ready to answer right now?
   * - dev mode: always ready (no MCP client attached locally);
   * - an active ai_wait lease: the agent is mid-turn on this call;
   * - a live MCP session registered under the agent's name;
   * - no presence provider wired: legacy behavior (always ready).
   */
  isAgentReadyForCall(callId: string, agentId: string): boolean {
    if (config.serviceToken === DEV_SERVICE_TOKEN) return true;
    if (this.getAiWaitStatus(callId).active) return true;
    const provider = this.agentPresenceProvider;
    if (!provider) return true;
    return provider().has(agentId);
  }

  /**
   * Builds the call_incoming payload. Anchored at resumedAt for callback
   * resumes so the phone gets a fresh ring window, exactly like the direct
   * resume path in LifecycleCoordinator.
   */
  private async pushCallIncoming(session: VoiceCallSession): Promise<boolean> {
    const diagPushStartMs = Date.now();
    logger.info({ callId: session.id, userId: session.userId }, '[diag:pushCallIncoming] start');
    const isCallback = session.resumedAt !== undefined;
    const anchor = session.resumedAt ?? session.createdAt;
    const payload: Record<string, unknown> = {
      type: 'call_incoming',
      callId: session.id,
      callerName: session.agentId,
      reason: session.reason,
      summary: session.context.summary,
      options: session.context.options,
      priority: session.priority,
      ...(isCallback ? { isCallback: true } : {}),
      ...(session.clientInfo ? { clientInfo: session.clientInfo } : {}),
      createdAt: anchor,
      expiresAt: new Date(Date.parse(anchor) + CALL_RING_TTL_MS).toISOString(),
    };
    const wsDelivered = this.deliverViaWs(session.userId, payload);
    let fcmOk = false;
    if (config.fcm.enabled) {
      try {
        logger.info({ callId: session.id, wsDelivered }, '[diag:fcm_send_start] before sendFcmPush');
        const result = await sendFcmPush(session.userId, payload);
        fcmOk = result.ok;
        const fcmMessageId = result.fcmMessageId;
        logger.info({ callId: session.id, userId: session.userId, wsDelivered, fcmOk, fcmMessageId, tokenRemoved: result.tokenRemoved, elapsedMs: Date.now() - diagPushStartMs }, '[ring] dispatch result');
        logger.info({ callId: session.id, fcmOk, fcmMessageId, elapsedMs: Date.now() - diagPushStartMs }, '[diag:pushCallIncoming] fcm result');
      } catch (err) {
        logger.warn({ callId: session.id, err: err instanceof Error ? err.message : String(err), elapsedMs: Date.now() - diagPushStartMs }, '[ring] FCM dispatch exception');
        logger.warn({ callId: session.id, elapsedMs: Date.now() - diagPushStartMs }, '[diag:pushCallIncoming] exception');
        fcmOk = false;
      }
    } else {
      logger.info({ callId: session.id, wsDelivered, elapsedMs: Date.now() - diagPushStartMs }, '[ring] dispatch via WS only (FCM disabled)');
    }
    logger.info({ callId: session.id, wsDelivered, fcmOk, elapsedMs: Date.now() - diagPushStartMs }, '[diag:pushCallIncoming] done');
    return wsDelivered || fcmOk;
  }

  /** WS-only delivery (no FCM). Used by pushCallIncoming which awaits FCM separately. */
  private deliverViaWs(userId: string, payload: Record<string, unknown>): boolean {
    const start = Date.now();
    const msgType = (payload.type as string) ?? 'unknown';
    publishNotificationRequested(userId, msgType, payload);
    const ws = phoneConnections.get(userId);
    if (ws && ws.readyState === WebSocket.OPEN) {
      try {
        ws.send(JSON.stringify({ type: msgType, payload, timestamp: new Date().toISOString() }));
        logger.info({ userId, msgType, elapsed: Date.now() - start }, '[WS] -> sent to phone (ring)');
        publishNotificationDelivered(userId, msgType);
        return true;
      } catch (sendErr) {
        const errMsg = sendErr instanceof Error ? sendErr.message : String(sendErr);
        logger.error({ err: sendErr, userId, msgType, elapsed: Date.now() - start }, '[WS] -> send failed (ring)');
        publishNotificationFailed(userId, msgType, errMsg);
        return false;
      }
    }
    logger.warn({ userId, msgType, readyState: ws?.readyState, elapsed: Date.now() - start }, '[WS] phone not connected, queuing notification (ring)');
    publishNotificationFailed(userId, msgType, 'phone not connected');
    const cutoff = Date.now() - QUEUED_NOTIFICATION_TTL_MS;
    const existing = pendingNotifications.get(userId) ?? [];
    const fresh = existing.filter((entry) => new Date(entry.timestamp).getTime() >= cutoff);
    fresh.push({ payload, timestamp: new Date().toISOString() });
    pendingNotifications.set(userId, fresh);
    return false;
  }

  /**
   * Ring gate: pushes call_incoming only while the agent is online/ready and
   * the call is still within its ring window. Otherwise schedules a bounded
   * retry. Re-entrant: also used as the retry callback itself.
   */
  async attemptRing(callId: string, attemptsLeft: number = MAX_RING_RETRIES): Promise<void> {
    const diagAttemptStartMs = Date.now();
    const session = await this.sessionRepo.findById(callId);
    if (!session || session.status !== 'pending') {
      logger.info({ callId, attemptsLeft, found: !!session, status: session?.status }, '[diag:attemptRing] no pending session, abort');
      return;
    }
    logger.info({ callId, attemptsLeft, agentId: session.agentId, status: session.status }, '[diag:attemptRing] entered');

    const anchorMs = Date.parse(session.resumedAt ?? session.createdAt);
    if (Date.now() >= anchorMs + CALL_RING_TTL_MS) {
      logger.info({ callId, userId: session.userId, elapsedMs: Date.now() - diagAttemptStartMs }, '[ring-gate] ring window expired; pending-TTL sweep will cancel');
      logger.info({ callId, attemptsLeft, elapsedMs: Date.now() - diagAttemptStartMs }, '[diag:attemptRing] window expired');
      return;
    }

    // Diagnostic: explicit readiness with reason (mirrors isAgentReadyForCall logic without side effects)
    const diagReady = this.isAgentReadyForCall(callId, session.agentId);
    let diagReason: string;
    if (config.serviceToken === DEV_SERVICE_TOKEN) diagReason = 'dev-mode-always-ready';
    else if (this.getAiWaitStatus(callId).active) diagReason = 'ai_wait_active';
    else if (!this.agentPresenceProvider) diagReason = 'no_presence_provider';
    else if (this.agentPresenceProvider().has(session.agentId)) diagReason = 'presence_has_agent';
    else diagReason = 'presence_missing_agent_offline';
    logger.info({ callId, agentId: session.agentId, ready: diagReady, reason: diagReason, attemptsLeft, aiWaitActive: this.getAiWaitStatus(callId).active, hasProvider: !!this.agentPresenceProvider }, '[diag:agent_ready] result');

    if (diagReady) {
      logger.info({ callId, agentId: session.agentId, elapsedMs: Date.now() - diagAttemptStartMs }, '[ring-gate] agent ready, pushing call_incoming');
      const dispatched = await this.pushCallIncoming(session);
      if (dispatched) {
        // Atomic transition: hold session lock while marking dispatched so a
        // concurrent cancelCallsByAgent cannot win after we succeeded.
        await withSessionLock(callId, async () => {
          const fresh = await this.sessionRepo.findById(callId);
          if (!fresh || fresh.status !== 'pending') return;
          if (fresh.ringDispatchedAt) return;
          fresh.ringDispatchedAt = now();
          await this.sessionRepo.save(fresh);
          logger.info({ callId, ringDispatchedAt: fresh.ringDispatchedAt }, '[ring-gate] ring dispatched - call now immune to MCP disconnect');
        });
        return;
      }
      // Dispatch failed (no WS and FCM not ok) - treat as transient and retry if budget remains
      logger.warn({ callId, agentId: session.agentId, elapsedMs: Date.now() - diagAttemptStartMs }, '[ring-gate] dispatch failed (no delivery) - scheduling retry');
      if (attemptsLeft > 0 && this.ringRetryScheduler) {
        const remaining = attemptsLeft - 1;
        logger.info({ callId, agentId: session.agentId, attemptsLeft, remaining, elapsedMs: Date.now() - diagAttemptStartMs }, '[diag:attemptRing] dispatch failed, scheduling retry');
        publishCallDelayed(session.userId, callId, 'agent_offline', remaining);
        this.ringRetryScheduler.schedule(`ring-retry:${callId}`, Date.now() + RING_RETRY_INTERVAL_MS, () => {
          void this.attemptRing(callId, remaining);
        });
        return;
      }
      logger.info({ callId, agentId: session.agentId, elapsedMs: Date.now() - diagAttemptStartMs }, '[ring-gate] dispatch retries exhausted; leaving to pending-TTL sweep');
      logger.info({ callId, attemptsLeft, elapsedMs: Date.now() - diagAttemptStartMs }, '[diag:attemptRing] dispatch exhausted');
      return;
    }

    if (attemptsLeft > 0 && this.ringRetryScheduler) {
      const remaining = attemptsLeft - 1;
      const delayReason: 'agent_offline' | 'agent_busy' = this.isAgentBusyElsewhere(callId)
        ? 'agent_busy'
        : 'agent_offline';
      logger.info(
        { callId, agentId: session.agentId, reason: delayReason, remaining, elapsedMs: Date.now() - diagAttemptStartMs },
        '[ring-gate] agent not ready; scheduling retry',
      );
      logger.info({ callId, agentId: session.agentId, reason: delayReason, attemptsLeft, remaining, elapsedMs: Date.now() - diagAttemptStartMs }, '[diag:agent_retry] scheduling');
      publishCallDelayed(session.userId, callId, delayReason, remaining);
      this.ringRetryScheduler.schedule(`ring-retry:${callId}`, Date.now() + RING_RETRY_INTERVAL_MS, () => {
        void this.attemptRing(callId, remaining);
      });
      return;
    }

    logger.info({ callId, agentId: session.agentId, elapsedMs: Date.now() - diagAttemptStartMs }, '[ring-gate] retries exhausted; leaving to pending-TTL sweep');
    logger.info({ callId, agentId: session.agentId, attemptsLeft, elapsedMs: Date.now() - diagAttemptStartMs }, '[diag:attemptRing] exhausted');
  }

  /**
   * In-process change notification for a call. The MCP send_message_and_wait
   * tool uses this to wake the moment a user message or a terminal transition
   * lands, instead of polling — the session repo remains the source of truth.
   * Safe because session locks (and the whole service) are in-process: a
   * render-free single instance guarantees the replying HTTP POST and the
   * waiting tool handler share this event bus.
   */
  createSessionWatcher(callId: string): SessionWatcher {
    const waiters = this.sessionChangeWaiters.get(callId) ?? new Set<() => void>();
    this.sessionChangeWaiters.set(callId, waiters);

    return {
      waitForChange: (timeoutMs: number): Promise<boolean> => {
        if (timeoutMs <= 0) return Promise.resolve(false);
        const since = this.sessionChangeCounters.get(callId) ?? 0;
        return new Promise((resolve) => {
          const cleanup = (): void => {
            clearTimeout(timer);
            waiters.delete(wake);
          };
          const wake = (): void => {
            if ((this.sessionChangeCounters.get(callId) ?? 0) <= since) return;
            cleanup();
            resolve(true);
          };
          const timer = setTimeout(() => {
            cleanup();
            resolve(false);
          }, timeoutMs);
          waiters.add(wake);
        });
      },
      dispose: (): void => {
        waiters.clear();
        if (waiters.size === 0) {
          this.sessionChangeWaiters.delete(callId);
        }
      },
    };
  }

  private signalSessionChange(callId: string): void {
    this.sessionChangeCounters.set(callId, (this.sessionChangeCounters.get(callId) ?? 0) + 1);
    this.sessionChangeWaiters.get(callId)?.forEach((wake) => wake());
  }

  /**
   * Registers an ai-wait lease and persists the wait fact (activeUntil/count)
   * onto the existing session row via the normal sessionRepo.save path — the
   * sessions.data JSONB blob carries the whole session object, so no separate
   * write path or migration is needed. Awaited by callers (notably the
   * send_message_and_wait tool) so the fact is durable before the AI message
   * is sent: a crash after the send still leaves "mid-wait + deadline" on the
   * row for restoreAiWaits() to pick back up. Best-effort: a repo failure is
   * logged, never thrown — the in-memory lease (the wake mechanism) still works.
   */
  async registerAiWait(callId: string, timeoutMs: number | null): Promise<() => Promise<void>> {
    const startedAt = now();
    const existing = this.aiWaitLeases.get(callId);
    // timeoutMs === null = turn-lease semantics (v2, ENGINE_V2): no client
    // window — the lease stays active until the waiter disposes it (reply /
    // call end / noactivity escalation) or the hard ceiling (maxTurnLeaseMs,
    // default 15 min) passes, so a crashed waiter's lease can never shield a
    // call from an agent-disconnect abort indefinitely. A live wait returns at
    // the noactivity escalation (5 min default) well before the ceiling, so it
    // never cuts a healthy conversation short. A newer registration must never
    // shorten an in-flight wait: with overlapping waits the status stays
    // active until the FURTHEST deadline, otherwise the banner would flip to
    // "not responding" while an older (longer) wait is still running. ISO
    // strings compare chronologically; activeUntil is always a real timestamp
    // (never null) after this change.
    const ceilingMs = config.v2.maxTurnLeaseMs;
    const candidateUntil =
      timeoutMs === null
        ? new Date(Date.now() + ceilingMs).toISOString()
        : new Date(Date.now() + Math.max(timeoutMs, 1000)).toISOString();
    const existingNewer =
      existing &&
      existing.count > 0 &&
      (existing.activeUntil === null || (candidateUntil !== null && existing.activeUntil > candidateUntil));
    const activeUntil = existingNewer ? existing.activeUntil : candidateUntil;
    this.aiWaitLeases.set(callId, {
      count: (existing?.count ?? 0) + 1,
      activeUntil,
      lastActiveAt: startedAt,
    });
    this.notifyAiWaitStatus(callId);
    await this.persistAiWaitState(callId);

    let disposed = false;
    return async () => {
      if (disposed) return;
      disposed = true;
      const current = this.aiWaitLeases.get(callId);
      if (!current) return;
      if (current.count <= 1) {
        this.aiWaitLeases.set(callId, {
          count: 0,
          activeUntil: current.activeUntil,
          lastActiveAt: now(),
        });
      } else {
        this.aiWaitLeases.set(callId, { ...current, count: current.count - 1 });
      }
      this.notifyAiWaitStatus(callId);
      await this.persistAiWaitState(callId);
    };
  }

  /**
   * Writes the current in-memory lease entry onto the session row (load,
   * mutate the aiWait* fields, save — the same save path every other mutation
   * uses), serialized per call so it can't interleave with addMessage-style
   * read-modify-write saves. A zero/absent count clears the durable fact.
   */
  private async persistAiWaitState(callId: string): Promise<void> {
    try {
      const entry = this.aiWaitLeases.get(callId);
      await withSessionLock(callId, async () => {
        const session = await this.sessionRepo.findById(callId);
        if (!session) return;
        if (entry && entry.count > 0) {
          session.aiWaitActiveUntil = entry.activeUntil;
          session.aiWaitCount = entry.count;
          session.aiWaitLastActiveAt = entry.lastActiveAt;
        } else {
          session.aiWaitActiveUntil = null;
          session.aiWaitCount = 0;
          session.aiWaitLastActiveAt = entry?.lastActiveAt ?? session.aiWaitLastActiveAt;
        }
        await this.sessionRepo.save(session);
      });
    } catch (err) {
      // Durability is best-effort: the live wait (map + watcher) is unaffected.
      logger.error({ err, callId }, '[aiWait] wait-state persist failed');
    }
  }

  /**
   * Rehydrates the in-memory lease map from durable wait facts after a
   * restart. RecoveryManager.loadFromDatabase already reloaded the rows
   * (including aiWait* fields) into the repo — this only revives the map
   * entries the live status/readiness checks read. Revives solely open calls
   * whose persisted deadline is still in the future; expired or terminal rows
   * keep their fact on the row but get no live lease. Call at boot after the
   * stale-session sweep so leases for already-swept calls are never revived.
   * Returns how many leases were revived.
   */
  async restoreAiWaits(nowMs: number = Date.now()): Promise<number> {
    const sessions = await this.sessionRepo.list();
    let restored = 0;
    for (const session of sessions) {
      if (session.status !== 'pending' && session.status !== 'active' && session.status !== 'paused') continue;
      const count = session.aiWaitCount ?? 0;
      const until = session.aiWaitActiveUntil;
      if (count <= 0 || !until) continue;
      if (Date.parse(until) <= nowMs) continue;
      this.aiWaitLeases.set(session.id, {
        count,
        activeUntil: until,
        lastActiveAt: session.aiWaitLastActiveAt ?? new Date(nowMs).toISOString(),
      });
      restored++;
    }
    if (restored > 0) {
      logger.info({ restored }, '[aiWait] restored wait leases from persisted session rows');
    }
    return restored;
  }

  getAiWaitStatus(callId: string): AiWaitStatus {
    const lease = this.aiWaitLeases.get(callId);
    if (!lease) return { active: false, activeUntil: null, lastActiveAt: null };
    const active = isLeaseActive(lease);
    return {
      active,
      activeUntil: active ? lease.activeUntil : null,
      lastActiveAt: lease.lastActiveAt,
    };
  }

  /**
   * Clears the durable wait fact on a session that is about to be saved as
   * terminal. Callers already drop the map entry; without this the row would
   * keep claiming "mid-wait" for a finished call.
   */
  private clearPersistedAiWait(session: VoiceCallSession): void {
    session.aiWaitActiveUntil = null;
    session.aiWaitCount = 0;
  }

  /**
   * Whether the agent is mid-turn on some OTHER call. Used to distinguish
   * "agent offline" from "agent busy" when the ring gate defers a call.
   */
  private isAgentBusyElsewhere(callId: string): boolean {
    for (const [otherCallId, lease] of this.aiWaitLeases) {
      if (otherCallId === callId) continue;
      if (isLeaseActive(lease)) return true;
    }
    return false;
  }

  private async notifyAiWaitStatus(callId: string): Promise<void> {
    try {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return;
      const status = this.getAiWaitStatus(callId);
      notifyPhone(session.userId, {
        type: 'ai_wait_status',
        callId,
        active: status.active,
        activeUntil: status.activeUntil,
        lastActiveAt: status.lastActiveAt,
        // Phase 2: lets the phone distinguish "agent is working on the call"
        // (active=true) from "agent is offline" (agentOnline=false).
        agentOnline: this.isAgentReadyForCall(callId, session.agentId),
      });
    } catch (err) {
      // Push is best-effort (fire-and-forget); a repo hiccup must not crash
      // the request path that owns the lease.
      logger.error({ err, callId }, '[aiWait] status notification failed');
    }
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
      clientInfo: input.clientInfo,
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
    logger.info({ callId: session.id, userId: session.userId, agentId: session.agentId, timestamp: new Date().toISOString() }, '[diag:create_call] created');

    await this.sessionRepo.create(session);
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[createCall] session stored');

    publishCallCreated(session.userId, session.id);

    logger.info({ callId: session.id, userId: session.userId, elapsed: Date.now() - start }, '[createCall] before ring gate');
    logger.info({ callId: session.id, attemptsLeft: MAX_RING_RETRIES, timestamp: new Date().toISOString() }, '[diag:attemptRing] scheduling initial');
    // Phase-2 gate: push the ring now if the agent is online/ready, otherwise
    // defer (bounded retries, then the pending-TTL sweep cancels the call).
    // Every call is agent-originated — the phone's outbound path was removed —
    // so the gate always applies.
    await this.attemptRing(session.id, MAX_RING_RETRIES);
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[createCall] after ring gate');
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[diag:create_call] after ring gate');

    logger.info({ callId: session.id, elapsed: Date.now() - start }, 'Call session created');
    return session;
  }

  async getCall(callId: string): Promise<VoiceCallSession | undefined> {
    return this.sessionRepo.findById(callId);
  }

  async getUserActiveCall(userId: string): Promise<VoiceCallSession | undefined> {
    const userSessions = await this.sessionRepo.findByUserId(userId);
    const cutoff = Date.now() - STALE_ACTIVE_THRESHOLD_MS;
    const pendingCutoff = Date.now() - PENDING_CALL_TTL_MS;
    return userSessions.find((s) => {
      if (s.status !== 'pending' && s.status !== 'active') return false;
      if (s.status === 'pending') {
        // An unanswered pending call is only "active" within its ring window;
        // otherwise a week-old call with refreshed activity would keep
        // surfacing on the phone's active-call poll and ring on reconnect.
        const becamePendingMs = new Date(s.resumedAt ?? s.createdAt).getTime();
        return becamePendingMs >= pendingCutoff;
      }
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
      if (session.status === 'completed' || session.status === 'cancelled' || session.status === 'aborted') return session;

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
    clientMessageId?: string,
  ): Promise<VoiceMessage | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      // Idempotency key: retries from the phone's persisted user-text queue
      // must never duplicate a message the backend already accepted.
      if (clientMessageId) {
        const existing = session.messages.find((m) => m.clientMessageId === clientMessageId);
        if (existing) {
          logger.info({ callId, clientMessageId }, 'Duplicate message ignored (idempotent)');
          return existing;
        }
      }

      const msg: VoiceMessage = {
        id: newId(),
        role,
        type,
        content,
        ...(clientMessageId ? { clientMessageId } : {}),
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
      this.signalSessionChange(callId);
      logger.info({ callId, role, type }, 'Message added to session');
      return msg;
    });
  }

  async addAiMessage(callId: string, content: string): Promise<VoiceMessage | undefined> {
    return this.addMessage(callId, 'ai', content, 'text');
  }

  async processTextMessage(callId: string, text: string, clientMessageId?: string): Promise<{ text: string }> {
    const session = await this.sessionRepo.findById(callId);
    if (!session) throw new Error(`Call session not found: ${callId}`);

    logger.info({ callId, text: text.slice(0, 100) }, '[STT] user text received');

    await this.addMessage(callId, 'user', text, 'text', clientMessageId);

    logger.info({ callId }, '[STT] text processed');
    return { text };
  }

  async scheduleCallback(params: CallbackRequest): Promise<boolean> {
    return withSessionLock(params.callId, async () => {
      const session = await this.sessionRepo.findById(params.callId);
      if (!session) return false;

      if (params.note && params.note.trim()) {
        session.messages.push({
          id: newId(),
          role: 'user',
          type: 'text',
          content: params.note,
          createdAt: now(),
        });
        session.lastActivityAt = now();
      }

      const resumeAt = Date.now() + params.delayMinutes * 60 * 1000;
      session.status = 'paused';
      session.pausedAt = now();
      await this.sessionRepo.save(session);
      await this.callbackRepo.save(session.userId, { callId: params.callId, resumeAt });
      this.signalSessionChange(params.callId);

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
      if (session.status === 'completed' || session.status === 'cancelled' || session.status === 'aborted') {
        logger.info({ callId, status: session.status }, 'Call session already terminal, no-op');
        return session;
      }

      session.status = 'completed';
      session.completedAt = now();
      session.retentionExpiresAt = new Date(Date.now() + COMPLETED_RETENTION_MS).toISOString();
      this.clearPersistedAiWait(session);

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
      // Lease map must not outlive the call: entries for terminal sessions
      // would otherwise accumulate unboundedly for the process lifetime.
      this.aiWaitLeases.delete(callId);
      publishCallEnded(session.userId, callId);
      this.signalSessionChange(callId);
      notifyPhone(session.userId, { type: 'call_ended', callId });
      logger.info({ callId }, 'Call session completed');      return session;
    });
  }

  async cancelCall(callId: string, note?: string, asExpired = false): Promise<VoiceCallSession | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      // Idempotent for terminal states: retries from the phone's decline queue
      // must never re-notify the phone or re-publish for an already-finished call.
      if (session.status === 'cancelled') {
        logger.info({ callId }, 'Call session already cancelled, no-op');
        return session;
      }
      if (session.status === 'completed' || session.status === 'aborted') {
        logger.info({ callId, status: session.status }, 'Call already terminal, ignoring cancel');
        return session;
      }

      // Stale-decline race (2026-09-11 live validation): a decline is only
      // meaningful while the call is still ringing. The phone fires its 60s
      // ring-timeout cancel from local state, but an AI message flips
      // pending->active server-side (addMessage) with no channel that can
      // reach a parked phone inside that window — so the timeout routinely
      // lands AFTER the answer and must not kill the live call. The same
      // holds for paused (previously answered) calls. Ending a live call is
      // completeCall/abortCall's job, never cancel's. Evaluated here, inside
      // the session lock, so a racing answer and cancel serialize and the
      // loser sees final truth — no TOCTOU. Returns the live session (200
      // semantics) so phone retry queues treat it resolved, not failed.
      if (session.status !== 'pending') {
        logger.info({ callId, status: session.status }, 'Call already live, ignoring stale cancel');
        return session;
      }

      // The decline note is recorded as a user message BEFORE the transition,
      // so it is part of the transcript the AI reads when it discovers the
      // call ended — and the pending-reply poll can deliver it inline.
      if (note && note.trim()) {
        session.messages.push({
          id: newId(),
          role: 'user',
          type: 'text',
          content: note,
          createdAt: now(),
        });
        session.lastActivityAt = now();
      }

      session.status = 'cancelled';
      session.completedAt = now();
      session.retentionExpiresAt = new Date(Date.now() + CANCELLED_RETENTION_MS).toISOString();
      this.clearPersistedAiWait(session);

      await this.sessionRepo.save(session);
      await this.callbackRepo.delete(session.userId);
      // Same cleanup as completeCall: a cancelled call needs no lease bookkeeping.
      this.aiWaitLeases.delete(callId);
      publishCallCancelled(session.userId, callId);
      this.signalSessionChange(callId);
      if (asExpired) {
        // Phase-2.3 missed-call semantics: the ring window closed before
        // anyone answered (pending-TTL sweep). The phone records an
        // `expired` outcome, distinct from an explicit cancel.
        notifyPhone(session.userId, { type: 'call_expired', callId, reason: 'ring_ttl_expired' });
      } else {
        notifyPhone(session.userId, { type: 'call_cancelled', callId });
      }
      logger.info({ callId }, 'Call session cancelled');
      return session;
    });
  }

  /**
   * Terminal state distinct from a user cancel: the owning agent deliberately
   * disconnected (its last MCP session was explicitly closed) while the call
   * was open. Sweep-driven presence loss never aborts — see registerMcpEndpoint.
   * Persisted as status 'aborted' and notified as call_aborted so the phone
   * can show "AI disconnected" instead of a generic cancelled/failed call.
   * Idempotent for terminal states, like cancelCall/completeCall.
   */
  async abortCall(callId: string, reason: string): Promise<VoiceCallSession | undefined> {
    return withSessionLock(callId, async () => {
      const session = await this.sessionRepo.findById(callId);
      if (!session) return undefined;

      if (session.status === 'aborted') {
        logger.info({ callId }, 'Call session already aborted, no-op');
        return session;
      }
      if (session.status === 'completed' || session.status === 'cancelled') {
        logger.info({ callId, status: session.status }, 'Call already terminal, ignoring abort');
        return session;
      }

      session.status = 'aborted';
      session.completedAt = now();
      session.retentionExpiresAt = new Date(Date.now() + CANCELLED_RETENTION_MS).toISOString();
      this.clearPersistedAiWait(session);

      await this.sessionRepo.save(session);
      await this.callbackRepo.delete(session.userId);
      // Same cleanup as completeCall/cancelCall: an aborted call needs no lease
      // bookkeeping, and any scheduled resume/expiry timers no-op (handleResume
      // re-checks status !== 'paused' before any side effect).
      this.aiWaitLeases.delete(callId);
      publishCallAborted(session.userId, callId, reason);
      this.signalSessionChange(callId);
      notifyPhone(session.userId, { type: 'call_aborted', callId, reason });
      logger.info({ callId, reason }, 'Call session aborted');
      return session;
    });
  }

  /**
   * Abort every open call owned by an agent whose last MCP session just closed.
   * Covers pending (unanswered rings), active (in conversation) and paused
   * (callback-scheduled) calls; a dead agent can neither answer a ring nor
   * resume a callback, so all three are terminal immediately. Skips calls with
   * an active ai_wait lease — a lease means the agent's waiter process is still
   * alive mid-turn on that call, so aborting would kill a live conversation
   * (the same guard the ring gate uses to consider the agent ready).
   * Returns how many calls were aborted.
   */
  async cancelCallsByAgent(agentId: string, reason: string): Promise<number> {
    const sessions = await this.sessionRepo.findByAgentId(agentId);
    let aborted = 0;
    let skippedDispatched = 0;
    for (const session of sessions) {
      if (session.status !== 'pending' && session.status !== 'active' && session.status !== 'paused') continue;
      if (this.getAiWaitStatus(session.id).active) continue;
      if (session.ringDispatchedAt) {
        skippedDispatched++;
        logger.info({ callId: session.id, agentId, ringDispatchedAt: session.ringDispatchedAt }, '[agent-disconnect] skipping dispatched call (MCP disconnect cannot cancel after ring)');
        continue;
      }
      await this.abortCall(session.id, reason);
      aborted++;
    }
    if (aborted > 0 || skippedDispatched > 0) {
      logger.info({ agentId, reason, aborted, skippedDispatched }, '[agent-disconnect] sweep result');
    }
    return aborted;
  }

  /**
   * True when the agent owns any pending/active/paused call. Used by the MCP
   * liveness sweep to decide whether a dead session needs closing NOW (it has
   * calls to protect) or can wait for the 30-min idle sweep.
   */
  async hasOpenCalls(agentId: string): Promise<boolean> {
    const sessions = await this.sessionRepo.findByAgentId(agentId);
    return sessions.some(
      (s) => s.status === 'pending' || s.status === 'active' || s.status === 'paused',
    );
  }

  /**
   * Drops every ai_wait lease held by the agent's calls. Called only on the
   * explicit-disconnect path (DELETE /mcp closed the agent's last session),
   * where the waiter process is gone and its dispose() will never run — so a
   * stale lease can't shield the calls from cancelCallsByAgent. Sweep-driven
   * presence loss never calls this (transport liveness is not call
   * lifecycle). Also clears the durable wait fact per disposed call so the
   * row stops claiming "mid-wait". Returns how many leases were
   * force-disposed.
   */
  async forceDisposeAiWaits(agentId: string): Promise<number> {
    const sessions = await this.sessionRepo.findByAgentId(agentId);
    let disposed = 0;
    for (const session of sessions) {
      if (this.aiWaitLeases.delete(session.id)) {
        disposed++;
        await this.persistAiWaitState(session.id);
      }
    }
    if (disposed > 0) {
      logger.info({ agentId, disposed }, '[agent-disconnect] force-disposed ai_wait leases');
    }
    return disposed;
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
    const pendingCutoff = nowMs - PENDING_CALL_TTL_MS;
    let completed = 0;

    for (const session of sessions) {
      if (session.status === 'pending') {
        // Hard TTL for unanswered rings: cancel rather than complete — the
        // phone never answered, so it must never be re-surfaced by the
        // active-call poll or a queued push after this window. Anchored at
        // resumedAt so callback resumes get a fresh ring window even when the
        // original call is old.
        const becamePendingMs = new Date(session.resumedAt ?? session.createdAt).getTime();
        if (becamePendingMs >= pendingCutoff) continue;
        await this.cancelCall(session.id, undefined, true);
        const ageMinutes = Math.max(1, Math.round((nowMs - becamePendingMs) / 60000));
        logger.info(
          { callId: session.id, userId: session.userId, ageMinutes },
          '[sweep] pending call ring-timeout, cancelled',
        );
        completed++;
        continue;
      }
      if (session.status !== 'active') continue;
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
    const session = await this.sessionRepo.delete(callId);
    if (session) {
      this.aiWaitLeases.delete(callId);
    }
    return session;
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

  // Flush any notifications queued while phone was disconnected — but only
  // those still inside the TTL. Stale events are dropped, never delivered: a
  // missed call is a silent miss, not a delayed ring.
  const queued = pendingNotifications.get(userId);
  if (queued && queued.length > 0) {
    pendingNotifications.delete(userId);
    const cutoff = Date.now() - QUEUED_NOTIFICATION_TTL_MS;
    const fresh = queued.filter((entry) => new Date(entry.timestamp).getTime() >= cutoff);
    const droppedCount = queued.length - fresh.length;
    if (droppedCount > 0) {
      logger.info({ userId, droppedCount }, '[WS] dropped expired queued notifications at flush');
    }
    const count = fresh.length;
    logger.info({ userId, count }, '[WS] flushing pending notifications');
    for (const { payload } of fresh) {
      notifyPhone(userId, payload);
    }
  }
}

export function notifyPhone(userId: string, payload: Record<string, unknown>): boolean {
  const start = Date.now();
  const msgType = (payload.type as string) ?? 'unknown';
  logger.info({ userId, msgType, phoneConnectionsSize: phoneConnections.size, hasConnection: phoneConnections.has(userId) }, '[notifyPhone] entered');

  publishNotificationRequested(userId, msgType, payload);

  // FCM push-to-wake — PRIMARY delivery for call_incoming (FCM-only idle model).
  // Sent as high-priority DATA message (fcm.ts: android priority high, data payload)
  // containing call_id, summary, reason, expiresAt so phone can fetch full
  // details via single REST call and show ring without any WS. The WS path
  // below is best-effort secondary (immediate if connected, queued for 2 min
  // otherwise); the phone dedupes via recentlyRung. For idle phones (no WS)
  // FCM is the ONLY leg — do NOT gate it on WS connection state.
  // Fire-and-forget: never awaited, never alters return value, silent no-op when FCM_ENABLED=false.
  if (msgType === 'call_incoming' && config.fcm.enabled) {
    void sendFcmPush(userId, payload);
  }

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
  // Queue for delivery when phone reconnects — pruning anything older than the
  // TTL first, so a long-offline backlog never bursts into the phone as a
  // series of stale rings.
  const cutoff = Date.now() - QUEUED_NOTIFICATION_TTL_MS;
  const existing = pendingNotifications.get(userId) ?? [];
  const fresh = existing.filter((entry) => new Date(entry.timestamp).getTime() >= cutoff);
  if (fresh.length !== existing.length) {
    logger.info({ userId, dropped: existing.length - fresh.length }, '[WS] pruned expired queued notifications');
  }
  fresh.push({ payload, timestamp: new Date().toISOString() });
  pendingNotifications.set(userId, fresh);
  logger.info({ userId, msgType, queueSize: fresh.length }, '[WS] notification queued for later delivery');
  return false;
}

/**
 * A wait lease is active while registered — with an explicit deadline only
 * until it passes, with `activeUntil: null` (v2 turn-lease mode) until the
 * waiter disposes it. Count guards overlapping registrations.
 */
function isLeaseActive(lease: { count: number; activeUntil: string | null }): boolean {
  return lease.count > 0 && (lease.activeUntil === null || Date.parse(lease.activeUntil) > Date.now());
}

export function isExpired(session: VoiceCallSession, now?: number): boolean {
  if (!session.retentionExpiresAt) return false;
  const nowMs = now ?? Date.now();
  return new Date(session.retentionExpiresAt).getTime() <= nowMs;
}
