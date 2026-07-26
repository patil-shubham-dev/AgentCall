export interface CallbackData {
  callId: string;
  resumeAt: number;
}

export interface CallbackEntry {
  userId: string;
  callId: string;
  resumeAt: number;
}

export interface CallbackRepository {
  findByUserId(userId: string): Promise<CallbackData | undefined>;
  save(userId: string, data: CallbackData): Promise<void>;
  delete(userId: string): Promise<void>;
  list(): Promise<CallbackEntry[]>;
  /** Run operations within a database transaction. In-memory repos execute directly. */
  transaction<T>(fn: () => Promise<T>): Promise<T>;
}

export class InMemoryCallbackRepository implements CallbackRepository {
  private callbacks = new Map<string, CallbackData>();

  async findByUserId(userId: string): Promise<CallbackData | undefined> {
    return this.callbacks.get(userId);
  }

  async save(userId: string, data: CallbackData): Promise<void> {
    this.callbacks.set(userId, data);
  }

  async delete(userId: string): Promise<void> {
    this.callbacks.delete(userId);
  }

  async list(): Promise<CallbackEntry[]> {
    const entries: CallbackEntry[] = [];
    for (const [userId, data] of this.callbacks) {
      entries.push({ userId, callId: data.callId, resumeAt: data.resumeAt });
    }
    return entries;
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    return fn();
  }
}
