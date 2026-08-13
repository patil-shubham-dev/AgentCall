import type { V2Event } from './events.js';

/**
 * Durable, replayable record of every v2 event (event-model §2). The single
 * source of truth for recovery/replay. M1 ships the in-process implementation;
 * a Postgres-backed store (05-database-schema.md) slots in behind this
 * interface for M3 without touching the engine.
 */
export interface EventLogStore {
  /** Appends and returns the fully-assigned event (sequence filled in). */
  append(callId: string, event: Omit<V2Event, 'sequence'>): Promise<V2Event>;
  /** All events for a call, in sequence order. */
  list(callId: string): Promise<V2Event[]>;
  /** Events strictly after `eventId` (cursor replay; at-least-once, consumers dedupe). */
  after(callId: string, eventId: string): Promise<V2Event[]>;
  /** True when the call has logged events (existence probe for 404 semantics). */
  exists(callId: string): boolean;
  count(callId: string): number;
}

export class InMemoryEventLogStore implements EventLogStore {
  private readonly logs = new Map<string, V2Event[]>();
  // eventId -> index within logs[callId], for O(1) cursor lookups.
  private readonly indexes = new Map<string, Map<string, number>>();

  async append(callId: string, event: Omit<V2Event, 'sequence'>): Promise<V2Event> {
    const entries = this.logs.get(callId) ?? [];
    const sequence = entries.length + 1; // contiguous per call — gaps are corruption
    const stored: V2Event = { ...event, sequence };
    entries.push(stored);
    this.logs.set(callId, entries);

    let index = this.indexes.get(callId);
    if (!index) {
      index = new Map();
      this.indexes.set(callId, index);
    }
    index.set(event.id, sequence - 1);
    return stored;
  }

  async list(callId: string): Promise<V2Event[]> {
    return [...(this.logs.get(callId) ?? [])];
  }

  async after(callId: string, eventId: string): Promise<V2Event[]> {
    const entries = this.logs.get(callId);
    if (!entries || entries.length === 0) return [];
    const index = this.indexes.get(callId);
    const position = index?.get(eventId);
    if (position === undefined) {
      // Unknown cursor: safest replay is the whole log (at-least-once; the
      // consumer dedupes by id). Never silently drop events.
      return [...entries];
    }
    return entries.slice(position + 1);
  }

  exists(callId: string): boolean {
    return (this.logs.get(callId)?.length ?? 0) > 0;
  }

  count(callId: string): number {
    return this.logs.get(callId)?.length ?? 0;
  }
}
