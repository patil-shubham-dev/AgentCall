import crypto from 'node:crypto';
import { logger } from './logger.js';
import type { EventBus, Event } from '../event-bus/index.js';

export interface EventPublisher {
  install(eventBus: EventBus): void;
  publish<T>(type: string, payload: T): void;
}

export function createEventPublisher(domain: string, version: number = 1): EventPublisher {
  let _eventBus: EventBus | undefined;

  function makeEvent<T>(type: string, payload: T): Event<T> {
    const now = new Date().toISOString();
    const id = crypto.randomUUID();
    return {
      type,
      version,
      payload,
      metadata: {
        eventId: id,
        timestamp: now,
        correlationId: id,
        source: domain,
      },
    };
  }

  return {
    install(eventBus: EventBus): void {
      _eventBus = eventBus;
    },

    publish<T>(type: string, payload: T): void {
      if (!_eventBus) return;
      const event = makeEvent<T>(type, payload);
      _eventBus.publish(event).catch((err) => {
        logger.error({ err, eventType: type, source: domain }, `EventBus publish failed (${type})`);
      });
    },
  };
}
