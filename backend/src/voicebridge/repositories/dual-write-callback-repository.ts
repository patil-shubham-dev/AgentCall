import { logger } from '../../common/logger.js';
import { withRetry } from '../../common/retry.js';
import type { MetricsCollector } from '../../common/metrics-collector.js';
import type { CallbackRepository, CallbackData, CallbackEntry } from './callback-repository.js';

export class DualWriteCallbackRepository implements CallbackRepository {
  private dualWriteFailures = 0;

  constructor(
    private memory: CallbackRepository,
    private database: CallbackRepository,
    private readFromDatabase: boolean = false,
    private metrics?: MetricsCollector,
  ) {}

  private get reader(): CallbackRepository {
    return this.readFromDatabase ? this.database : this.memory;
  }

  private async writeToDatabase<T>(label: string, fn: () => Promise<T>): Promise<void> {
    try {
      await withRetry(fn, `dual-write.${label}`, { maxRetries: 1, baseDelayMs: 100 });
    } catch (err) {
      this.dualWriteFailures++;
      logger.error(
        { err, operation: label, totalFailures: this.dualWriteFailures },
        `[DualWriteCallbackRepository] database ${label} failed after retry`,
      );
    }
  }

  async findByUserId(userId: string): Promise<CallbackData | undefined> {
    return this.reader.findByUserId(userId);
  }

  async save(userId: string, data: CallbackData): Promise<void> {
    await this.memory.save(userId, data);
    await this.writeToDatabase('save', () => this.database.save(userId, data));
  }

  async delete(userId: string): Promise<void> {
    await this.memory.delete(userId);
    await this.writeToDatabase('delete', () => this.database.delete(userId));
  }

  async list(): Promise<CallbackEntry[]> {
    return this.reader.list();
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return this.database.transaction(fn);
  }
}
