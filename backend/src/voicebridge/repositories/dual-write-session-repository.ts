import { logger } from '../../common/logger.js';
import { withRetry } from '../../common/retry.js';
import type { MetricsCollector } from '../../common/metrics-collector.js';
import type { VoiceCallSession } from '../types.js';
import type { SessionRepository } from './session-repository.js';

export class DualWriteSessionRepository implements SessionRepository {
  private dualWriteFailures = 0;

  constructor(
    private memory: SessionRepository,
    private database: SessionRepository,
    private readFromDatabase: boolean = false,
    private metrics?: MetricsCollector,
  ) {}

  private get reader(): SessionRepository {
    return this.readFromDatabase ? this.database : this.memory;
  }

  private async writeToDatabase<T>(label: string, fn: () => Promise<T>): Promise<void> {
    try {
      await withRetry(fn, `dual-write.${label}`, { maxRetries: 1, baseDelayMs: 100 });
    } catch (err) {
      this.dualWriteFailures++;
      this.metrics?.incrementCounter('dual-write.failures');
      logger.error(
        { err, operation: label, totalFailures: this.dualWriteFailures },
        `[DualWriteSessionRepository] database ${label} failed after retry`,
      );
    }
  }

  async findById(callId: string): Promise<VoiceCallSession | undefined> {
    return this.reader.findById(callId);
  }

  async findByUserId(userId: string): Promise<VoiceCallSession[]> {
    return this.reader.findByUserId(userId);
  }

  async findByAgentId(agentId: string): Promise<VoiceCallSession[]> {
    return this.reader.findByAgentId(agentId);
  }

  async list(): Promise<VoiceCallSession[]> {
    return this.reader.list();
  }

  async create(session: VoiceCallSession): Promise<void> {
    await this.memory.create(session);
    await this.writeToDatabase('create', () => this.database.create(session));
  }

  async save(session: VoiceCallSession): Promise<void> {
    await this.memory.save(session);
    await this.writeToDatabase('save', () => this.database.save(session));
  }

  async delete(callId: string): Promise<VoiceCallSession | undefined> {
    const session = await this.memory.delete(callId);
    await this.writeToDatabase('delete', () => this.database.delete(callId));
    return session;
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return this.database.transaction(fn);
  }
}
