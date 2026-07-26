import { logger } from '../../common/logger.js';
import { withRetry } from '../../common/retry.js';
import type { MetricsCollector } from '../../common/metrics-collector.js';
import type { CallbackRepository, CallbackData, CallbackEntry } from './callback-repository.js';

const SLOW_QUERY_THRESHOLD_MS = 250;

export class InstrumentedCallbackRepository implements CallbackRepository {
  constructor(
    private inner: CallbackRepository,
    private metrics?: MetricsCollector,
  ) {}

  async findByUserId(userId: string): Promise<CallbackData | undefined> {
    return this.track('findByUserId', () => this.inner.findByUserId(userId), { userId });
  }

  async save(userId: string, data: CallbackData): Promise<void> {
    return this.track('save', () => this.inner.save(userId, data), { userId, callId: data.callId });
  }

  async delete(userId: string): Promise<void> {
    return this.track('delete', () => this.inner.delete(userId), { userId });
  }

  async list(): Promise<CallbackEntry[]> {
    return this.track('list', () => this.inner.list());
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
        `callback.${operation}`,
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
    this.metrics?.recordTiming(`callback.${operation}`, duration);
    if (success) {
      this.metrics?.incrementCounter(`callback.${operation}.ok`);
    } else {
      this.metrics?.incrementCounter(`callback.${operation}.error`);
    }
    if (duration > SLOW_QUERY_THRESHOLD_MS) {
      logger.warn(
        { operation: `callback.${operation}`, duration, threshold: SLOW_QUERY_THRESHOLD_MS },
        '[SlowQuery] callback repository operation exceeded threshold',
      );
    }
  }
}
