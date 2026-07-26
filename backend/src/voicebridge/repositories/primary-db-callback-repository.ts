import { logger } from '../../common/logger.js';
import type { CallbackRepository, CallbackData, CallbackEntry } from './callback-repository.js';
import type { DatabaseCallbackRepository } from './db-callback-repository.js';

export class PrimaryDatabaseCallbackRepository implements CallbackRepository {
  constructor(private db: DatabaseCallbackRepository) {}

  async findByUserId(userId: string): Promise<CallbackData | undefined> {
    const result = await this.db.findByUserId(userId);
    logger.debug({ userId, found: !!result }, '[PrimaryDatabaseCallbackRepository] findByUserId');
    return result;
  }

  async save(userId: string, data: CallbackData): Promise<void> {
    await this.db.save(userId, data);
    logger.debug({ userId, callId: data.callId }, '[PrimaryDatabaseCallbackRepository] save');
  }

  async delete(userId: string): Promise<void> {
    await this.db.delete(userId);
    logger.debug({ userId }, '[PrimaryDatabaseCallbackRepository] delete');
  }

  async list(): Promise<CallbackEntry[]> {
    const result = await this.db.list();
    logger.debug({ count: result.length }, '[PrimaryDatabaseCallbackRepository] list');
    return result;
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return this.db.transaction(fn);
  }
}
