import { logger } from '../common/logger.js';
import type { EventLogStore } from './event-log.js';
import type { V2Event } from './events.js';
import { validateEventPayload } from './events.js';

export type EventHandler = (event: V2Event) => void | Promise<void>;

export interface EventPlaneSubscription {
  readonly callId: string;
  unsubscribe(): void;
}

export interface EventPlaneOptions {
  /** Replay on subscribe: 'none' (live only) | 'all' (from the first event) | after an event id. */
  replay?: 'none' | 'all' | { afterEventId: string };
}

/**
 * The event transport for v2 (README §4 "EventPlane"). In-process by default
 * ($0 constraint); a Redis Streams adapter implements the same surface at M5.
 * Guarantees:
 *  - every published event is first appended to the event log (outbox write),
 *  - per-call total order (publish is serialized per call via the log append),
 *  - at-least-once delivery — subscribers dedupe by event id.
 */
export class EventPlane {
  private readonly subscribers = new Map<string, Set<{ id: number; handler: EventHandler }>>();
  private nextSubscriberId = 1;
  private readonly pendingWrites = new Map<string, Promise<unknown>>();

  constructor(private readonly log: EventLogStore) {}

  /** Appends to the log (outbox), then delivers to per-call subscribers. */
  async publish(callId: string, event: Omit<V2Event, 'sequence'>): Promise<V2Event> {
    validateEventPayload(event.type, event.payload);

    // Serialize appends per call so `sequence` is assigned in a deterministic
    // order even when two commands race (total order per call, roadmap R6).
    const previous = this.pendingWrites.get(callId) ?? Promise.resolve();
    const write = previous.then(() => this.log.append(callId, event));
    this.pendingWrites.set(callId, write.catch(() => undefined));
    // Drop the resolved entry so abandoned calls don't leak map memory; only
    // remove if it is still the latest write (a newer one may have chained on).
    void write.finally(() => {
      if (this.pendingWrites.get(callId) === write) this.pendingWrites.delete(callId);
    });
    const stored = await write;

    const callSubs = this.subscribers.get(callId);
    if (callSubs) {
      for (const sub of callSubs) {
        try {
          await sub.handler(stored);
        } catch (err) {
          logger.error({ err, callId, eventType: event.type }, '[v2.event-plane] subscriber handler error');
        }
      }
    }
    return stored;
  }

  subscribe(callId: string, handler: EventHandler, options: EventPlaneOptions = {}): EventPlaneSubscription {
    const replayOption = options.replay ?? 'none';
    let callSubs = this.subscribers.get(callId);
    if (!callSubs) {
      callSubs = new Set();
      this.subscribers.set(callId, callSubs);
    }
    const id = this.nextSubscriberId++;
    const delivered = new Set<string>();
    // While a replay is in flight, live events are buffered (never delivered
    // ahead of the replay prefix) — preserves per-call total order even when a
    // command is mid-flight during subscription. After the replay completes
    // the buffer flushes in append order (its events all have higher sequence
    // than the snapshot), then delivery goes live.
    const buffer: V2Event[] = [];
    let replaying = replayOption !== 'none';
    const guarded: EventHandler = async (event) => {
      if (delivered.has(event.id)) return;
      if (replaying) {
        buffer.push(event);
        return;
      }
      delivered.add(event.id);
      await handler(event);
    };
    const entry = { id, handler: guarded };
    callSubs.add(entry);

    if (replaying) {
      void (async () => {
        try {
          const events =
            replayOption === 'all'
              ? await this.log.list(callId)
              : replayOption !== 'none'
                ? await this.log.after(callId, replayOption.afterEventId)
                : [];
          for (const event of events) {
            if (delivered.has(event.id)) continue;
            delivered.add(event.id);
            await handler(event);
          }
          // Snapshot delivered — open the live path, then flush the buffer.
          // The flip is synchronous, so nothing interleaves at the boundary.
          replaying = false;
          const pending = buffer.splice(0);
          for (const event of pending) {
            if (delivered.has(event.id)) continue;
            delivered.add(event.id);
            await handler(event);
          }
        } catch (err) {
          logger.error({ err, callId }, '[v2.event-plane] replay failed');
        }
      })();
    }

    return {
      callId,
      unsubscribe: () => {
        const subs = this.subscribers.get(callId);
        subs?.delete(entry);
        if (subs && subs.size === 0) {
          this.subscribers.delete(callId);
        }
      },
    };
  }

  subscriberCount(callId: string): number {
    return this.subscribers.get(callId)?.size ?? 0;
  }
}
