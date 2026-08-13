import { describe, it, expect } from 'vitest';
import { InMemoryEventLogStore } from '../v2/event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { uuidV7 } from '../v2/ids.js';
import type { V2Event } from '../v2/events.js';

function makeEvent(callId: string, type: string, payload: Record<string, unknown> = {}): Omit<V2Event, 'sequence'> {
  return {
    id: uuidV7(),
    type,
    version: 1,
    call_id: callId,
    correlation_id: callId,
    occurred_at: new Date().toISOString(),
    actor: { type: 'system' },
    payload,
  };
}

describe('v2 event log', () => {
  it('assigns contiguous per-call sequences in append order', async () => {
    const log = new InMemoryEventLogStore();
    const a = await log.append('call-1', makeEvent('call-1', 'call.created'));
    const b = await log.append('call-1', makeEvent('call-1', 'call.ringing'));
    const c = await log.append('call-1', makeEvent('call-1', 'call.connected'));
    expect([a.sequence, b.sequence, c.sequence]).toEqual([1, 2, 3]);

    // A second call has its own sequence space.
    const other = await log.append('call-2', makeEvent('call-2', 'call.created'));
    expect(other.sequence).toBe(1);
  });

  it('replays events strictly after a cursor id', async () => {
    const log = new InMemoryEventLogStore();
    const first = await log.append('call-1', makeEvent('call-1', 'call.created'));
    await log.append('call-1', makeEvent('call-1', 'call.ringing'));
    const third = await log.append('call-1', makeEvent('call-1', 'call.connected'));

    const after = await log.after('call-1', first.id);
    expect(after.map((e) => e.sequence)).toEqual([2, 3]);
    expect(after.at(-1)?.id).toBe(third.id);
  });

  it('returns the whole log for an unknown cursor (never silently drops)', async () => {
    const log = new InMemoryEventLogStore();
    await log.append('call-1', makeEvent('call-1', 'call.created'));
    const after = await log.after('call-1', 'unknown-event-id');
    expect(after).toHaveLength(1);
  });
});

describe('v2 event plane', () => {
  it('delivers live events to subscribers in publish order', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    const received: V2Event[] = [];
    plane.subscribe('call-1', (e) => received.push(e));

    await plane.publish('call-1', makeEvent('call-1', 'call.created'));
    await plane.publish('call-1', makeEvent('call-1', 'call.ringing'));

    expect(received.map((e) => e.type)).toEqual(['call.created', 'call.ringing']);
    expect(received.map((e) => e.sequence)).toEqual([1, 2]);
  });

  it('replays from the start for a fresh consumer, then continues live', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    await plane.publish('call-1', makeEvent('call-1', 'call.created'));

    const received: V2Event[] = [];
    plane.subscribe('call-1', (e) => received.push(e), { replay: 'all' });
    await plane.publish('call-1', makeEvent('call-1', 'call.connected'));

    expect(received.map((e) => e.type)).toEqual(['call.created', 'call.connected']);
  });

  it('resumes from a cursor with exactly-once delivery for one subscriber', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    const a = await plane.publish('call-1', makeEvent('call-1', 'call.created'));
    const b = await plane.publish('call-1', makeEvent('call-1', 'call.ringing'));

    const received: V2Event[] = [];
    plane.subscribe('call-1', (e) => received.push(e), { replay: { afterEventId: a.id } });
    const c = await plane.publish('call-1', makeEvent('call-1', 'call.connected'));

    expect(received.map((e) => e.id)).toEqual([b.id, c.id]);
  });

  it('stops delivering after unsubscribe', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    const received: V2Event[] = [];
    const sub = plane.subscribe('call-1', (e) => received.push(e));

    await plane.publish('call-1', makeEvent('call-1', 'call.created'));
    sub.unsubscribe();
    await plane.publish('call-1', makeEvent('call-1', 'call.ringing'));

    expect(received).toHaveLength(1);
    expect(plane.subscriberCount('call-1')).toBe(0);
  });

  it('buffers events published mid-replay so per-call order is preserved', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    await plane.publish('call-1', makeEvent('call-1', 'call.created')); // sequence 1

    const received: V2Event[] = [];
    plane.subscribe(
      'call-1',
      async (e) => {
        received.push(e);
        // Publish while the replay is still delivering: this live event must
        // be buffered and flushed AFTER the replay prefix, never interleaved.
        if (e.sequence === 1) {
          await plane.publish('call-1', makeEvent('call-1', 'call.ringing')); // sequence 2
        }
      },
      { replay: 'all' },
    );
    await new Promise((r) => setTimeout(r, 20));

    expect(received.map((e) => e.type)).toEqual(['call.created', 'call.ringing']);
    expect(received.map((e) => e.sequence)).toEqual([1, 2]);
  });

  it('keeps per-call order when events race (serialized appends)', async () => {
    const plane = new EventPlane(new InMemoryEventLogStore());
    const received: V2Event[] = [];
    plane.subscribe('call-1', (e) => received.push(e));

    await Promise.all([
      plane.publish('call-1', makeEvent('call-1', 'message.queued', { message_id: 'm1' })),
      plane.publish('call-1', makeEvent('call-1', 'message.queued', { message_id: 'm2' })),
    ]);

    expect(received.map((e) => e.sequence)).toEqual([1, 2]);
    expect(received.map((e) => (e.payload as { message_id: string }).message_id).sort()).toEqual(['m1', 'm2']);
  });

  it('keeps total order when a live event races the post-replay buffer flush', async () => {
    // Deterministic race: gate list() so the replay snapshot is frozen at [1,2],
    // publish 3 while the replay is blocked (it must buffer), then release.
    // The handler for buffered event 3 publishes event 4 BEFORE recording 3 —
    // with a naive flip-then-flush the live 4 overtook the async flush and the
    // subscriber observed [1,2,4,3].
    class GatedLogStore extends InMemoryEventLogStore {
      gate: Promise<void> = Promise.resolve();
      override async list(callId: string): Promise<V2Event[]> {
        await this.gate;
        return super.list(callId);
      }
    }
    const log = new GatedLogStore();
    const plane = new EventPlane(log);
    await plane.publish('call-1', makeEvent('call-1', 'call.created')); // seq 1
    await plane.publish('call-1', makeEvent('call-1', 'call.ringing')); // seq 2

    let release: () => void = () => {};
    log.gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const received: V2Event[] = [];
    plane.subscribe(
      'call-1',
      async (e) => {
        if (e.sequence === 3) {
          await plane.publish('call-1', makeEvent('call-1', 'call.connected')); // seq 4
        }
        received.push(e);
      },
      { replay: 'all' },
    );

    // Replay is blocked awaiting list(); this live event must buffer behind
    // the snapshot, never overtake it.
    await plane.publish('call-1', makeEvent('call-1', 'call.answer.requested')); // seq 3
    release();
    await new Promise((r) => setTimeout(r, 20));

    expect(received.map((e) => e.sequence)).toEqual([1, 2, 3, 4]);
  });
});
