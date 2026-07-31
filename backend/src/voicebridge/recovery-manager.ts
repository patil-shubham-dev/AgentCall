import { logger } from '../common/logger.js';
import type { CleanupScheduler } from '../common/cleanup-scheduler.js';
import type { SessionRepository, CallbackRepository } from './repositories/index.js';
import type { LifecycleCoordinator } from './lifecycle-coordinator.js';

export class RecoveryManager {
  constructor(
    private dbSessionRepo: SessionRepository,
    private dbCallbackRepo: CallbackRepository,
    private memorySessionRepo: SessionRepository,
    private memoryCallbackRepo: CallbackRepository,
  ) {}

  async loadFromDatabase(): Promise<{ sessions: number; callbacks: number }> {
    const dbSessions = await this.dbSessionRepo.list();
    for (const session of dbSessions) {
      await this.memorySessionRepo.create(session);
    }

    const dbCallbacks = await this.dbCallbackRepo.list();
    for (const cb of dbCallbacks) {
      await this.memoryCallbackRepo.save(cb.userId, { callId: cb.callId, resumeAt: cb.resumeAt });
    }

    logger.info(
      { sessions: dbSessions.length, callbacks: dbCallbacks.length },
      '[RecoveryManager] loaded state from database',
    );

    return { sessions: dbSessions.length, callbacks: dbCallbacks.length };
  }

  async rebuildTimers(
    cleanupScheduler: CleanupScheduler,
    lifecycleCoordinator: LifecycleCoordinator,
  ): Promise<void> {
    await this.rebuildCallbackTimers(lifecycleCoordinator);
    await this.rebuildOrphanedPauseTimers(lifecycleCoordinator);
    logger.info('[RecoveryManager] timers rebuilt');
  }

  private async rebuildCallbackTimers(
    lifecycleCoordinator: LifecycleCoordinator,
  ): Promise<void> {
    const callbacks = await this.memoryCallbackRepo.list();
    for (const cb of callbacks) {
      const session = await this.memorySessionRepo.findById(cb.callId);
      if (!session) {
        logger.warn({ callId: cb.callId }, '[RecoveryManager] callback session not found, skipping');
        continue;
      }
      const pausedMs = session.pausedAt ? new Date(session.pausedAt).getTime() : Date.now();
      const resumeAtMs = Number(cb.resumeAt);
      const delayMinutes = Math.max(1, Math.floor((resumeAtMs - pausedMs) / 60000));

      lifecycleCoordinator.resumeCallback(cb.userId, cb.callId, delayMinutes, resumeAtMs);
      logger.info(
        { callId: cb.callId, userId: cb.userId, resumeAt: cb.resumeAt, delayMinutes },
        '[RecoveryManager] rebuilt callback timer',
      );
    }
  }

  private async rebuildOrphanedPauseTimers(
    lifecycleCoordinator: LifecycleCoordinator,
  ): Promise<void> {
    const sessions = await this.memorySessionRepo.list();
    const callbackCallIds = new Set<string>();
    const callbacks = await this.memoryCallbackRepo.list();
    for (const cb of callbacks) {
      callbackCallIds.add(cb.callId);
    }

    for (const session of sessions) {
      if (session.status !== 'paused') continue;
      if (callbackCallIds.has(session.id)) continue;
      if (!session.pausedAt) {
        logger.warn({ callId: session.id }, '[RecoveryManager] paused session missing pausedAt, skipping');
        continue;
      }

      await lifecycleCoordinator.recoverOrphanedPause(session.id, session.pausedAt);
      logger.info(
        { callId: session.id, userId: session.userId, pausedAt: session.pausedAt },
        '[RecoveryManager] rebuilt orphaned pause timer',
      );
    }
  }
}
