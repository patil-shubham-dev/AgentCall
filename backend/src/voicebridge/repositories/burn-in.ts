import crypto from 'node:crypto';
import { logger } from '../../common/logger.js';
import type { VoiceCallSession } from '../types.js';
import type { SessionRepository } from './session-repository.js';
import type { CallbackRepository } from './callback-repository.js';
import type { PersistenceVerifier, VerificationMetrics } from './verifier.js';

export interface ScenarioResult {
  name: string;
  pass: boolean;
  metrics: VerificationMetrics;
  error?: string;
}

export interface BurnInReport {
  overallPass: boolean;
  scenarios: ScenarioResult[];
  cleanupResult?: ScenarioResult;
  totalDurationMs: number;
}

function now(): string {
  return new Date().toISOString();
}

function newId(): string {
  return crypto.randomUUID();
}

function buildSession(overrides: Partial<VoiceCallSession> & { userId: string; id: string }): VoiceCallSession {
  return {
    id: overrides.id,
    userId: overrides.userId,
    agentId: overrides.agentId ?? 'burn-in-agent',
    status: overrides.status ?? 'pending',
    priority: overrides.priority ?? 'normal',
    reason: overrides.reason ?? 'clarification',
    context: overrides.context ?? { summary: 'burn-in validation' },
    messages: overrides.messages ?? [],
    createdAt: overrides.createdAt ?? now(),
    connectedAt: overrides.connectedAt,
    pausedAt: overrides.pausedAt,
    resumedAt: overrides.resumedAt,
    completedAt: overrides.completedAt,
    retentionExpiresAt: overrides.retentionExpiresAt,
    result: overrides.result,
  };
}

export class PersistenceBurnIn {
  private createdSessionIds: string[] = [];
  private createdCallbackUserIds: string[] = [];
  private scenarioResults: ScenarioResult[] = [];

  constructor(
    private sessionRepo: SessionRepository,
    private callbackRepo: CallbackRepository,
    private verifier: PersistenceVerifier,
  ) {}

  async run(): Promise<BurnInReport> {
    const start = Date.now();
    logger.info('[PersistenceBurnIn] starting burn-in validation');

    const scenarios = [
      { name: 'create-session', fn: () => this.scenarioCreateSession() },
      { name: 'update-session-status', fn: () => this.scenarioUpdateStatus() },
      { name: 'pause-with-callback', fn: () => this.scenarioPauseWithCallback() },
      { name: 'resume-callback-delete', fn: () => this.scenarioResumeDeleteCallback() },
      { name: 'complete-session', fn: () => this.scenarioCompleteSession() },
      { name: 'cancel-session', fn: () => this.scenarioCancelSession() },
      { name: 'delete-session', fn: () => this.scenarioDeleteSession() },
      { name: 'retention-expired-sweep', fn: () => this.scenarioRetentionExpired() },
      { name: 'multiple-sessions', fn: () => this.scenarioMultipleSessions() },
    ];

    for (const { name, fn } of scenarios) {
      try {
        await fn();
      } catch (err) {
        const metrics: VerificationMetrics = {
          durationMs: Date.now() - start,
          sessionsInMemory: 0,
          sessionsInDatabase: 0,
          callbacksInMemory: 0,
          callbacksInDatabase: 0,
          mismatches: [],
          dbQueryFailures: 0,
        };
        this.scenarioResults.push({
          name,
          pass: false,
          metrics,
          error: err instanceof Error ? err.message : String(err),
        });
        logger.error({ err, scenario: name }, '[PersistenceBurnIn] scenario failed');
      }
    }

    const cleanupResult = await this.cleanup();

    const overallPass = this.scenarioResults.every((s) => s.pass) && (cleanupResult?.pass ?? true);

    const report: BurnInReport = {
      overallPass,
      scenarios: this.scenarioResults,
      cleanupResult,
      totalDurationMs: Date.now() - start,
    };

    logger.info(
      {
        overallPass,
        scenarioCount: this.scenarioResults.length,
        passed: this.scenarioResults.filter((s) => s.pass).length,
        failed: this.scenarioResults.filter((s) => !s.pass).length,
        totalDurationMs: report.totalDurationMs,
      },
      '[PersistenceBurnIn] burn-in complete',
    );

    return report;
  }

  private async verifyAndRecord(scenarioName: string): Promise<void> {
    const metrics = await this.verifier.verify();
    const pass =
      metrics.mismatches.length === 0 &&
      metrics.dbQueryFailures === 0;

    this.scenarioResults.push({ name: scenarioName, pass, metrics });

    if (!pass) {
      logger.warn(
        { scenario: scenarioName, mismatchCount: metrics.mismatches.length, dbQueryFailures: metrics.dbQueryFailures },
        '[PersistenceBurnIn] verification found issues',
      );
    }
  }

  private async scenarioCreateSession(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'pending' });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    await this.verifyAndRecord('create-session');
  }

  private async scenarioUpdateStatus(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'pending' });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    session.status = 'active';
    session.connectedAt = now();
    await this.sessionRepo.save(session);

    await this.verifyAndRecord('update-session-status');
  }

  private async scenarioPauseWithCallback(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'paused', pausedAt: now() });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    const resumeAt = Date.now() + 3600000;
    await this.callbackRepo.save(userId, { callId: id, resumeAt });
    this.createdCallbackUserIds.push(userId);

    await this.verifyAndRecord('pause-with-callback');
  }

  private async scenarioResumeDeleteCallback(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'paused', pausedAt: now() });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    const resumeAt = Date.now() + 3600000;
    await this.callbackRepo.save(userId, { callId: id, resumeAt });
    this.createdCallbackUserIds.push(userId);

    session.status = 'pending';
    session.resumedAt = now();
    await this.sessionRepo.save(session);
    await this.callbackRepo.delete(userId);

    await this.verifyAndRecord('resume-callback-delete');
  }

  private async scenarioCompleteSession(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'active', connectedAt: now() });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    session.status = 'completed';
    session.completedAt = now();
    session.retentionExpiresAt = new Date(Date.now() + 3600000).toISOString();
    session.result = { transcriptSummary: 'burn-in test call' };
    await this.sessionRepo.save(session);

    await this.verifyAndRecord('complete-session');
  }

  private async scenarioCancelSession(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'pending' });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    session.status = 'cancelled';
    session.completedAt = now();
    session.retentionExpiresAt = new Date(Date.now() + 300000).toISOString();
    await this.sessionRepo.save(session);

    await this.verifyAndRecord('cancel-session');
  }

  private async scenarioDeleteSession(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const session = buildSession({ id, userId, status: 'completed', completedAt: now(), retentionExpiresAt: new Date(Date.now() + 3600000).toISOString() });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    await this.sessionRepo.delete(id);

    await this.verifyAndRecord('delete-session');
  }

  private async scenarioRetentionExpired(): Promise<void> {
    const id = newId();
    const userId = `burn-in-user-${id.slice(0, 8)}`;

    const past = new Date(Date.now() - 7200000).toISOString();
    const session = buildSession({ id, userId, status: 'completed', completedAt: past, retentionExpiresAt: past });
    await this.sessionRepo.create(session);
    this.createdSessionIds.push(id);

    const deleted = await this.sessionRepo.delete(id);
    if (!deleted) {
      throw new Error('session not found for deletion in retention-expired scenario');
    }

    await this.verifyAndRecord('retention-expired-sweep');
  }

  private async scenarioMultipleSessions(): Promise<void> {
    const baseUserId = `burn-in-user-multi-${newId().slice(0, 8)}`;

    const s1 = buildSession({ id: newId(), userId: `${baseUserId}-1`, status: 'pending' });
    const s2 = buildSession({ id: newId(), userId: `${baseUserId}-2`, status: 'active', connectedAt: now() });
    const s3 = buildSession({ id: newId(), userId: `${baseUserId}-3`, status: 'completed', completedAt: now(), retentionExpiresAt: new Date(Date.now() + 3600000).toISOString() });

    for (const s of [s1, s2, s3]) {
      await this.sessionRepo.create(s);
      this.createdSessionIds.push(s.id);
    }

    s1.status = 'active';
    s1.connectedAt = now();
    await this.sessionRepo.save(s1);

    await this.callbackRepo.save(s2.userId, { callId: s2.id, resumeAt: Date.now() + 3600000 });
    this.createdCallbackUserIds.push(s2.userId);

    await this.verifyAndRecord('multiple-sessions');
  }

  private async cleanup(): Promise<ScenarioResult | undefined> {
    if (this.createdSessionIds.length === 0 && this.createdCallbackUserIds.length === 0) {
      return undefined;
    }

    logger.info(
      { sessionsToClean: this.createdSessionIds.length, callbacksToClean: this.createdCallbackUserIds.length },
      '[PersistenceBurnIn] cleaning up test data',
    );

    for (const userId of this.createdCallbackUserIds) {
      try {
        await this.callbackRepo.delete(userId);
      } catch (err) {
        logger.error({ err, userId }, '[PersistenceBurnIn] callback cleanup failed');
      }
    }

    for (const sessionId of this.createdSessionIds) {
      try {
        await this.sessionRepo.delete(sessionId);
      } catch (err) {
        logger.error({ err, sessionId }, '[PersistenceBurnIn] session cleanup failed');
      }
    }

    let metrics: VerificationMetrics;
    try {
      metrics = await this.verifier.verify();
    } catch (err) {
      return {
        name: 'cleanup',
        pass: false,
        metrics: {
          durationMs: 0,
          sessionsInMemory: 0,
          sessionsInDatabase: 0,
          callbacksInMemory: 0,
          callbacksInDatabase: 0,
          mismatches: [],
          dbQueryFailures: 0,
        },
        error: err instanceof Error ? err.message : String(err),
      };
    }

    const pass = metrics.mismatches.length === 0 && metrics.dbQueryFailures === 0;

    return { name: 'cleanup', pass, metrics };
  }
}
