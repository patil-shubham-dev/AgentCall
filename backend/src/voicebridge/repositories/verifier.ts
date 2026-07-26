import { logger } from '../../common/logger.js';
import type { VoiceCallSession } from '../types.js';
import type { InMemorySessionRepository } from './session-repository.js';
import type { InMemoryCallbackRepository } from './callback-repository.js';
import type { DatabaseSessionRepository } from './db-session-repository.js';
import type { DatabaseCallbackRepository } from './db-callback-repository.js';
import type { CallbackEntry } from './callback-repository.js';

export interface Mismatch {
  type: string;
  id: string;
  details: string;
}

export interface VerificationMetrics {
  durationMs: number;
  sessionsInMemory: number;
  sessionsInDatabase: number;
  callbacksInMemory: number;
  callbacksInDatabase: number;
  mismatches: Mismatch[];
  dbQueryFailures: number;
}

export interface PersistenceVerifierOptions {
  memorySessionRepo: InMemorySessionRepository;
  dbSessionRepo: DatabaseSessionRepository;
  memoryCallbackRepo: InMemoryCallbackRepository;
  dbCallbackRepo: DatabaseCallbackRepository;
  intervalMs?: number;
}

function normalizeTimestamp(ts: string | null | undefined): string | undefined {
  if (ts === null || ts === undefined) return undefined;
  return ts;
}

function compareSessions(
  memory: VoiceCallSession,
  db: VoiceCallSession,
): Mismatch[] {
  const result: Mismatch[] = [];
  const id = memory.id;

  if (memory.status !== db.status) {
    result.push({
      type: 'status_mismatch',
      id,
      details: `memory="${memory.status}" db="${db.status}"`,
    });
  }

  const memRetention = normalizeTimestamp(memory.retentionExpiresAt);
  const dbRetention = normalizeTimestamp(db.retentionExpiresAt);
  if (memRetention !== dbRetention) {
    result.push({
      type: 'retention_mismatch',
      id,
      details: `memory="${memRetention ?? '(none)'}" db="${dbRetention ?? '(none)'}"`,
    });
  }

  const tsFields: Array<{ field: string; mem: string | null | undefined; db: string | null | undefined }> = [
    { field: 'createdAt', mem: memory.createdAt, db: db.createdAt },
    { field: 'connectedAt', mem: memory.connectedAt, db: db.connectedAt },
    { field: 'completedAt', mem: memory.completedAt, db: db.completedAt },
    { field: 'pausedAt', mem: memory.pausedAt, db: db.pausedAt },
    { field: 'resumedAt', mem: memory.resumedAt, db: db.resumedAt },
  ];

  for (const { field, mem, db: dbVal } of tsFields) {
    if (normalizeTimestamp(mem) !== normalizeTimestamp(dbVal)) {
      result.push({
        type: 'timestamp_mismatch',
        id,
        details: `field="${field}" memory="${normalizeTimestamp(mem) ?? '(none)'}" db="${normalizeTimestamp(dbVal) ?? '(none)'}"`,
      });
    }
  }

  return result;
}

function compareCallbacks(
  memEntry: CallbackEntry,
  dbEntry: CallbackEntry,
): Mismatch[] {
  const result: Mismatch[] = [];
  const userId = memEntry.userId;

  if (memEntry.callId !== dbEntry.callId) {
    result.push({
      type: 'callback_field_mismatch',
      id: userId,
      details: `field="callId" memory="${memEntry.callId}" db="${dbEntry.callId}"`,
    });
  }

  if (memEntry.resumeAt !== dbEntry.resumeAt) {
    result.push({
      type: 'callback_field_mismatch',
      id: userId,
      details: `field="resumeAt" memory=${memEntry.resumeAt} db=${dbEntry.resumeAt}`,
    });
  }

  return result;
}

export class PersistenceVerifier {
  private handle: NodeJS.Timeout | null = null;

  constructor(private options: PersistenceVerifierOptions) {
    if (options.intervalMs && options.intervalMs > 0) {
      this.handle = setInterval(() => {
        this.verify().catch((err) => {
          logger.error({ err }, '[PersistenceVerifier] periodic check failed');
        });
      }, options.intervalMs);
      this.handle.unref();
    }
  }

  stop(): void {
    if (this.handle) {
      clearInterval(this.handle);
      this.handle = null;
    }
  }

  async verify(): Promise<VerificationMetrics> {
    const start = Date.now();
    let dbQueryFailures = 0;

    let memSessions: VoiceCallSession[] = [];
    let dbSessions: VoiceCallSession[] = [];
    let memCallbacks: CallbackEntry[] = [];
    let dbCallbacks: CallbackEntry[] = [];

    try {
      memSessions = await this.options.memorySessionRepo.list();
    } catch (err) {
      logger.error({ err }, '[PersistenceVerifier] failed to list sessions from memory');
      return {
        durationMs: Date.now() - start,
        sessionsInMemory: 0,
        sessionsInDatabase: 0,
        callbacksInMemory: 0,
        callbacksInDatabase: 0,
        mismatches: [],
        dbQueryFailures: 1,
      };
    }

    try {
      dbSessions = await this.options.dbSessionRepo.list();
    } catch (err) {
      logger.error({ err }, '[PersistenceVerifier] failed to list sessions from database');
      dbQueryFailures++;
    }

    try {
      memCallbacks = await this.options.memoryCallbackRepo.list();
    } catch (err) {
      logger.error({ err }, '[PersistenceVerifier] failed to list callbacks from memory');
    }

    try {
      dbCallbacks = await this.options.dbCallbackRepo.list();
    } catch (err) {
      logger.error({ err }, '[PersistenceVerifier] failed to list callbacks from database');
      dbQueryFailures++;
    }

    const mismatches: Mismatch[] = [];

    const dbSessionMap = new Map<string, VoiceCallSession>();
    for (const s of dbSessions) {
      dbSessionMap.set(s.id, s);
    }
    const memSessionIds = new Set(memSessions.map((s) => s.id));

    for (const memSession of memSessions) {
      const dbSession = dbSessionMap.get(memSession.id);
      if (!dbSession) {
        mismatches.push({
          type: 'session_missing_in_db',
          id: memSession.id,
          details: `status="${memSession.status}"`,
        });
        continue;
      }
      mismatches.push(...compareSessions(memSession, dbSession));
    }

    for (const dbSession of dbSessions) {
      if (!memSessionIds.has(dbSession.id)) {
        mismatches.push({
          type: 'session_missing_in_memory',
          id: dbSession.id,
          details: `status="${dbSession.status}"`,
        });
      }
    }

    const dbCallbackMap = new Map<string, CallbackEntry>();
    for (const cb of dbCallbacks) {
      dbCallbackMap.set(cb.userId, cb);
    }
    const memCallbackUserIds = new Set(memCallbacks.map((cb) => cb.userId));
    const dbCallbackUserIds = new Set(dbCallbacks.map((cb) => cb.userId));

    for (const memCb of memCallbacks) {
      const dbCb = dbCallbackMap.get(memCb.userId);
      if (!dbCb) {
        mismatches.push({
          type: 'callback_missing_in_db',
          id: memCb.userId,
          details: `callId="${memCb.callId}"`,
        });
        continue;
      }
      mismatches.push(...compareCallbacks(memCb, dbCb));
    }

    for (const userId of dbCallbackUserIds) {
      if (!memCallbackUserIds.has(userId)) {
        const dbCb = dbCallbackMap.get(userId);
        if (dbCb) {
          mismatches.push({
            type: 'callback_missing_in_memory',
            id: userId,
            details: `callId="${dbCb.callId}"`,
          });
        }
      }
    }

    const metrics: VerificationMetrics = {
      durationMs: Date.now() - start,
      sessionsInMemory: memSessions.length,
      sessionsInDatabase: dbSessions.length,
      callbacksInMemory: memCallbacks.length,
      callbacksInDatabase: dbCallbacks.length,
      mismatches,
      dbQueryFailures,
    };

    if (mismatches.length === 0 && dbQueryFailures === 0) {
      logger.info(
        {
          durationMs: metrics.durationMs,
          sessionsCompared: memSessions.length + dbSessions.length,
          callbacksCompared: memCallbacks.length + dbCallbacks.length,
        },
        '[PersistenceVerifier] verified — no mismatches',
      );
    } else {
      logger.warn(
        {
          durationMs: metrics.durationMs,
          sessionsInMemory: metrics.sessionsInMemory,
          sessionsInDatabase: metrics.sessionsInDatabase,
          callbacksInMemory: metrics.callbacksInMemory,
          callbacksInDatabase: metrics.callbacksInDatabase,
          mismatchCount: mismatches.length,
          mismatches: mismatches.map((m) => `${m.type}:${m.id}=${m.details}`),
          dbQueryFailures,
        },
        '[PersistenceVerifier] verification found differences',
      );
    }

    return metrics;
  }
}
