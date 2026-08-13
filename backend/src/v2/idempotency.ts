import { config } from '../common/config.js';

/**
 * Idempotency-Key store (docs/v2/04-api-spec.md §1): the server stores the
 * first response per (identity, key, call_id) and replays it for 24h with
 * `X-Idempotent-Replay: true`. M1 ships the in-memory store; a durable store
 * slots in behind this interface when persistence lands (M3).
 */
export interface StoredIdempotentResponse {
  statusCode: number;
  body: unknown;
  storedAt: number;
}

export class IdempotencyStore {
  private readonly entries = new Map<string, StoredIdempotentResponse>();

  constructor(private readonly ttlMs: number = config.v2.idempotencyTtlMs) {}

  /** Composite key: an idempotency key is scoped to (identity, key, call). */
  key(identity: string, idempotencyKey: string, callId?: string): string {
    return `${identity}\u0000${callId ?? ''}\u0000${idempotencyKey}`;
  }

  /** Returns the stored response if present and unexpired. */
  get(key: string): StoredIdempotentResponse | undefined {
    const entry = this.entries.get(key);
    if (!entry) return undefined;
    if (Date.now() - entry.storedAt >= this.ttlMs) {
      this.entries.delete(key);
      return undefined;
    }
    return entry;
  }

  put(key: string, statusCode: number, body: unknown): void {
    this.entries.set(key, { statusCode, body, storedAt: Date.now() });
    // Opportunistic size guard: drop expired entries when the map grows large.
    if (this.entries.size > 10_000) {
      this.sweep();
    }
  }

  sweep(): number {
    const cutoff = Date.now() - this.ttlMs;
    let removed = 0;
    for (const [key, entry] of this.entries) {
      if (entry.storedAt < cutoff) {
        this.entries.delete(key);
        removed++;
      }
    }
    return removed;
  }

  size(): number {
    return this.entries.size;
  }
}
