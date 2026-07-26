import { logger } from '../../common/logger.js';
import type { VoiceCallSession } from '../types.js';
import type { SessionRepository } from './session-repository.js';
import type { DatabaseSessionRepository } from './db-session-repository.js';

export class PrimaryDatabaseSessionRepository implements SessionRepository {
  constructor(private db: DatabaseSessionRepository) {}

  async findById(callId: string): Promise<VoiceCallSession | undefined> {
    const result = await this.db.findById(callId);
    logger.debug({ callId, found: !!result }, '[PrimaryDatabaseSessionRepository] findById');
    return result;
  }

  async findByUserId(userId: string): Promise<VoiceCallSession[]> {
    const result = await this.db.findByUserId(userId);
    logger.debug({ userId, count: result.length }, '[PrimaryDatabaseSessionRepository] findByUserId');
    return result;
  }

  async list(): Promise<VoiceCallSession[]> {
    const result = await this.db.list();
    logger.debug({ count: result.length }, '[PrimaryDatabaseSessionRepository] list');
    return result;
  }

  async create(session: VoiceCallSession): Promise<void> {
    await this.db.create(session);
    logger.debug({ callId: session.id }, '[PrimaryDatabaseSessionRepository] create');
  }

  async save(session: VoiceCallSession): Promise<void> {
    await this.db.save(session);
    logger.debug({ callId: session.id, status: session.status }, '[PrimaryDatabaseSessionRepository] save');
  }

  async delete(callId: string): Promise<VoiceCallSession | undefined> {
    const session = await this.db.delete(callId);
    logger.debug({ callId, deleted: !!session }, '[PrimaryDatabaseSessionRepository] delete');
    return session;
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return this.db.transaction(fn);
  }
}
