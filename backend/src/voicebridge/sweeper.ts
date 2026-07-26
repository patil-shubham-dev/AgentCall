import { logger } from '../common/logger.js';
import type { VoiceCallSession } from './types.js';
import type { DeletionCoordinator } from './coordinator.js';
import type { SessionRepository } from './repositories/index.js';

export interface SessionSweeperOptions {
  repository: SessionRepository;
  isExpired: (session: VoiceCallSession) => boolean;
  coordinator: DeletionCoordinator;
  intervalMs: number;
}

export class SessionSweeper {
  private handle: NodeJS.Timeout | null = null;

  constructor(private options: SessionSweeperOptions) {}

  start(): void {
    if (this.handle) return;
    this.handle = setInterval(() => this.sweep(), this.options.intervalMs);
    this.handle.unref();
    logger.info({ intervalMs: this.options.intervalMs }, '[SessionSweeper] started');
  }

  stop(): void {
    if (this.handle) {
      clearInterval(this.handle);
      this.handle = null;
      logger.info('[SessionSweeper] stopped');
    }
  }

  async sweep(): Promise<void> {
    const sessions = await this.options.repository.list();
    let deletedCount = 0;
    for (const session of sessions) {
      if (!this.options.isExpired(session)) continue;

      await this.options.repository.delete(session.id);
      this.options.coordinator.handleDeleted(session);
      deletedCount++;
    }

    if (deletedCount > 0) {
      logger.info({ expiredCount: deletedCount }, '[SessionSweeper] sweep complete');
    }
  }
}
