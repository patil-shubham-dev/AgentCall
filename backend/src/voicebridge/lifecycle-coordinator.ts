import type { CleanupScheduler } from '../common/cleanup-scheduler.js';
import { publishCallResumed, publishCallExpired } from './calls/publisher.js';
import type { SessionRepository, CallbackRepository } from './repositories/index.js';
import { CALL_RING_TTL_MS } from './service.js';

export class LifecycleCoordinator {
  constructor(
    private cleanupScheduler: CleanupScheduler,
    private sessionRepo: SessionRepository,
    private callbackRepo: CallbackRepository,
    private notifyPhone: (userId: string, payload: Record<string, unknown>) => boolean,
    /**
     * Phase-2 ring gate hook: when injected, resumed callbacks go through
     * VoiceBridgeService.attemptRing so the ring only fires once the agent is
     * online/ready. Without it (tests, legacy wiring) the coordinator pushes
     * directly as before.
     */
    private ringCall?: (callId: string) => void | Promise<void>,
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

    const resumedAtIso = new Date().toISOString();
    existing.status = 'pending';
    existing.resumedAt = resumedAtIso;
    publishCallResumed(userId, callId, delayMinutes, new Date(resumeAt).toISOString());
    if (this.ringCall) {
      // Gated path: rings only when the agent is online/ready (attemptRing
      // re-checks the ring window and retries while the agent is offline).
      await this.ringCall(callId);
    } else {
      // Legacy path (tests without a gate): push directly.
      this.notifyPhone(userId, {
        type: 'call_incoming',
        callId,
        callerName: existing.agentId,
        reason: existing.reason,
        summary: existing.context.summary,
        options: existing.context.options,
        priority: existing.priority,
        isCallback: true,
        // The resume re-creates the ring window: the phone must treat this as a
        // fresh call, not the original (possibly days-old) one.
        createdAt: resumedAtIso,
        expiresAt: new Date(Date.now() + CALL_RING_TTL_MS).toISOString(),
      });
    }
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
