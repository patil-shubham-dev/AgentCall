import type { VoiceCallSession } from '../types.js';

export interface SessionRepository {
  findById(callId: string): Promise<VoiceCallSession | undefined>;
  findByUserId(userId: string): Promise<VoiceCallSession[]>;
  list(): Promise<VoiceCallSession[]>;
  create(session: VoiceCallSession): Promise<void>;
  save(session: VoiceCallSession): Promise<void>;
  delete(callId: string): Promise<VoiceCallSession | undefined>;
  /** Run operations within a database transaction. In-memory repos execute directly. */
  transaction<T>(fn: () => Promise<T>): Promise<T>;
}

export class InMemorySessionRepository implements SessionRepository {
  private sessions = new Map<string, VoiceCallSession>();

  async findById(callId: string): Promise<VoiceCallSession | undefined> {
    return this.sessions.get(callId);
  }

  async findByUserId(userId: string): Promise<VoiceCallSession[]> {
    const results: VoiceCallSession[] = [];
    for (const session of this.sessions.values()) {
      if (session.userId === userId) {
        results.push(session);
      }
    }
    return results;
  }

  async list(): Promise<VoiceCallSession[]> {
    return Array.from(this.sessions.values());
  }

  async create(session: VoiceCallSession): Promise<void> {
    this.sessions.set(session.id, session);
  }

  async save(session: VoiceCallSession): Promise<void> {
    this.sessions.set(session.id, session);
  }

  async delete(callId: string): Promise<VoiceCallSession | undefined> {
    const session = this.sessions.get(callId);
    if (!session) return undefined;
    this.sessions.delete(callId);
    return session;
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return fn();
  }
}
