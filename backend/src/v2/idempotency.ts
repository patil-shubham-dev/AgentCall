import { config } from '../common/config.js';

/**
 * Idempotency-Key store (docs/v2/04-api-spec.md §1): the server stores the
 * first response per (identity, key, call_id) and replays it for 24h with
 * `X-Idempotent-Replay: true`. The in-memory store is the M1 default; the
 * Postgres store (roadmap M3) implements the same backend so HTTP replays
 * survive a worker restart.
 */
export interface StoredIdempotentResponse {
  statusCode: number;
  body: unknown;
  storedAt: number;
}

/** The backend surface both the in-memory and Postgres stores implement. */
export interface IdempotencyBackend {
  /** Composite key: an idempotency key is scoped to (identity, key, call). */
  key(identity: string, idempotencyKey: string, callId?: string): string;
  /** Returns the stored response if present and unexpired. */
  get(key: string): Promise<StoredIdempotentResponse | undefined>;
  /** Stores the first response for a key (first-write-wins under races). */
  put(key: string, statusCode: number, body: unknown): Promise<void>;
  /** Removes expired entries; returns how many. */
  sweep(): Promise<number>;
  size(): Promise<number>;
}

/**
 * Shared key format — both stores must agree on it (replay across restart).
 * Hex-encoded parts with `:` separators: deterministic, injective, and safe
 * for PostgreSQL TEXT (a NUL byte would be rejected) while staying readable.
 */
export function composeIdempotencyKey(identity: string, key: string, callId?: string): string {
  const hex = (s: string): string => Buffer.from(s, 'utf8').toString('hex');
  return `${hex(identity)}:${hex(callId ?? '')}:${hex(key)}`;
}

export class IdempotencyStore implements IdempotencyBackend {
  private readonly entries = new Map<string, StoredIdempotentResponse>();

  constructor(
    protected readonly ttlMs: number = config.v2.idempotencyTtlMs,
    /** Hard cap (dev/memory modes): oldest entries are evicted beyond this. */
    private readonly maxEntries: number = config.v2.idempotencyMaxEntries,
  ) {}

  key(identity: string, idempotencyKey: string, callId?: string): string {
    return composeIdempotencyKey(identity, idempotencyKey, callId);
  }

  async get(key: string): Promise<StoredIdempotentResponse | undefined> {
    const entry = this.entries.get(key);
    if (!entry) return undefined;
    if (Date.now() - entry.storedAt >= this.ttlMs) {
      this.entries.delete(key);
      return undefined;
    }
    return entry;
  }

  async put(key: string, statusCode: number, body: unknown): Promise<void> {
    this.entries.set(key, { statusCode, body, storedAt: Date.now() });
    // Opportunistic size guard: drop expired entries when the map grows large.
    if (this.entries.size > 10_000) {
      await this.sweep();
    }
    // Hard cap (dev/memory modes): beyond maxEntries the OLDEST entries are
    // evicted (Map preserves insertion order). Replay protection degrades for
    // those keys, never the process — bounded memory beats unlimited growth in
    // a long-running dev/staging process.
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }
  }

  async sweep(): Promise<number> {
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

  async size(): Promise<number> {
    return this.entries.size;
  }
}
