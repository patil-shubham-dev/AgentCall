import { logger } from '../../common/logger.js';
import { withRetry } from '../../common/retry.js';
import type { MetricsCollector } from '../../common/metrics-collector.js';
import type { VoiceCallSession } from '../types.js';
import type { SessionRepository } from './session-repository.js';

const SLOW_QUERY_THRESHOLD_MS = 250;

export class InstrumentedSessionRepository implements SessionRepository {
  constructor(
    private inner: SessionRepository,
    private metrics?: MetricsCollector,
  ) {}

  async findById(callId: string): Promise<VoiceCallSession | undefined> {
    return this.track('findById', () => this.inner.findById(callId), { callId });
  }

  async findByUserId(userId: string): Promise<VoiceCallSession[]> {
    return this.track('findByUserId', () => this.inner.findByUserId(userId), { userId });
  }

  async findByAgentId(agentId: string): Promise<VoiceCallSession[]> {
    return this.track('findByAgentId', () => this.inner.findByAgentId(agentId), { agentId });
  }

  async list(): Promise<VoiceCallSession[]> {
    return this.track('list', () => this.inner.list());
  }

  async create(session: VoiceCallSession): Promise<void> {
    return this.track('create', () => this.inner.create(session), { callId: session.id });
  }

  async save(session: VoiceCallSession): Promise<void> {
    return this.track('save', () => this.inner.save(session), { callId: session.id });
  }

  async delete(callId: string): Promise<VoiceCallSession | undefined> {
    return this.track('delete', () => this.inner.delete(callId), { callId });
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return this.inner.transaction(fn);
  }

  private async track<T>(
    operation: string,
    fn: () => Promise<T>,
    _context?: Record<string, unknown>,
  ): Promise<T> {
    const start = Date.now();
    try {
      const result = await withRetry(
        () => fn(),
        `session.${operation}`,
        { maxRetries: 1 },
      );
      const duration = Date.now() - start;
      this.recordMetrics(operation, duration, true);
      return result;
    } catch (err) {
      const duration = Date.now() - start;
      this.recordMetrics(operation, duration, false);
      this.metrics?.incrementCounter('repo.errors');
      throw err;
    }
  }

  private recordMetrics(operation: string, duration: number, success: boolean): void {
    this.metrics?.recordTiming(`session.${operation}`, duration);
    if (success) {
      this.metrics?.incrementCounter(`session.${operation}.ok`);
    } else {
      this.metrics?.incrementCounter(`session.${operation}.error`);
    }
    if (duration > SLOW_QUERY_THRESHOLD_MS) {
      logger.warn(
        { operation: `session.${operation}`, duration, threshold: SLOW_QUERY_THRESHOLD_MS },
        '[SlowQuery] session repository operation exceeded threshold',
      );
    }
  }
}
