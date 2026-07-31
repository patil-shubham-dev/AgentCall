import type { CleanupScheduler } from '../common/cleanup-scheduler.js';
import { publishCallResumed, publishCallExpired } from './calls/publisher.js';
import type { SessionRepository, CallbackRepository } from './repositories/index.js';

export class LifecycleCoordinator {
  constructor(
    private cleanupScheduler: CleanupScheduler,
    private sessionRepo: SessionRepository,
    private callbackRepo: CallbackRepository,
    private notifyPhone: (userId: string, payload: Record<string, unknown>) => boolean,
  ) {}

  resumeCallback(userId: string, callId: string, delayMinutes: number, resumeAt: number | string): void {
    const resumeAtMs = typeof resumeAt === 'number' ? resumeAt : Number(resumeAt);
    if (Number.isNaN(resumeAtMs)) {
      throw new TypeError(`Invalid resumeAt for callback ${callId}: ${resumeAt}`);
    }
    this.cleanupScheduler.schedule(`resume:${callId}`, resumeAtMs, () => {
      this.handleResume(userId, callId, delayMinutes, resumeAtMs);
    });

    const pauseTtlMs = 24 * 60 * 60 * 1000;
    this.cleanupScheduler.schedule(`pause-ttl:${callId}`, resumeAtMs + pauseTtlMs, () => {
      this.handlePauseExpiry(userId, callId, resumeAtMs, delayMinutes);
    });
  }

  private async handleResume(userId: string, callId: string, delayMinutes: number, resumeAt: number): Promise<void> {
    const existing = await this.sessionRepo.findById(callId);
    if (!existing || existing.status !== 'paused') return;

    existing.status = 'pending';
    existing.resumedAt = new Date().toISOString();
    publishCallResumed(userId, callId, delayMinutes, new Date(resumeAt).toISOString());
    this.notifyPhone(userId, {
      type: 'call_incoming',
      callId,
      reason: existing.reason,
      summary: existing.context.summary,
      options: existing.context.options,
      priority: existing.priority,
      isCallback: true,
    });
    await this.callbackRepo.delete(userId);
  }

  private async handlePauseExpiry(userId: string, callId: string, resumeAt: number, delayMinutes: number): Promise<void> {
    const existing = await this.sessionRepo.findById(callId);
    if (!existing || existing.status !== 'paused') return;

    const pauseStartedAt = resumeAt - delayMinutes * 60 * 1000;
    const pausedDurationMinutes = Math.floor((Date.now() - pauseStartedAt) / 60000);

    existing.status = 'cancelled';
    existing.completedAt = new Date().toISOString();
    existing.retentionExpiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString();

    publishCallExpired(userId, callId, 'paused_ttl_expired', pausedDurationMinutes);
    this.notifyPhone(userId, {
      type: 'call_expired',
      callId,
      reason: 'paused_ttl_expired',
    });
    await this.callbackRepo.delete(userId);
  }

  async recoverOrphanedPause(callId: string, pausedAt: string): Promise<void> {
    const existing = await this.sessionRepo.findById(callId);
    if (!existing || existing.status !== 'paused') return;

    const pausedMs = new Date(pausedAt).getTime();
    const pauseTtlMs = 24 * 60 * 60 * 1000;
    const expiryAt = pausedMs + pauseTtlMs;

    if (expiryAt <= Date.now()) {
      await this.handlePauseExpiry(existing.userId, callId, pausedMs, 0);
      return;
    }

    this.cleanupScheduler.schedule(`pause-ttl:${callId}`, expiryAt, () => {
      this.handlePauseExpiry(existing.userId, callId, pausedMs, 0);
    });
  }
}
