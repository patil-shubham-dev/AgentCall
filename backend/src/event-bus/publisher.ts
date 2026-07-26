import { randomUUID } from 'node:crypto';
import type { Event } from './types.js';
import type { EventBus } from './bus.js';

export class Publisher {
  constructor(
    private bus: EventBus,
    private source: string,
  ) {}

  async publish<T>(
    type: string,
    version: number,
    payload: T,
    options?: {
      correlationId?: string;
      causationId?: string;
    },
  ): Promise<Event<T>> {
    const event: Event<T> = {
      type,
      version,
      payload,
      metadata: {
        eventId: randomUUID(),
        timestamp: new Date().toISOString(),
        correlationId: options?.correlationId ?? randomUUID(),
        causationId: options?.causationId,
        source: this.source,
      },
    };

    await this.bus.publish(event);

    return event;
  }

  withSource(source: string): Publisher {
    return new Publisher(this.bus, source);
  }
}
