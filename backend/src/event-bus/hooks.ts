import { logger } from '../common/logger.js';
import type {
  Event,
  PublishResult,
  BeforeEventHook,
  AfterEventHook,
  ErrorHook,
} from './types.js';

export interface EventLoggerHooks {
  before: BeforeEventHook;
  after: AfterEventHook;
  error: ErrorHook;
}

export function createEventLoggerHook(): EventLoggerHooks {
  const before: BeforeEventHook = (event: Event) => {
    logger.debug(
      {
        eventType: event.type,
        eventId: event.metadata.eventId,
        correlationId: event.metadata.correlationId,
        source: event.metadata.source,
      },
      `[EventBus] dispatching ${event.type}`,
    );
  };

  const after: AfterEventHook = (event: Event, result: PublishResult) => {
    if (result.errors.length > 0) {
      logger.warn(
        {
          eventType: event.type,
          eventId: event.metadata.eventId,
          errors: result.errors.map((e) => ({
            handler: e.handlerName,
            error: e.error.message,
          })),
        },
        `[EventBus] ${event.type} completed with ${result.errors.length} error(s)`,
      );
    } else {
      logger.debug(
        {
          eventType: event.type,
          eventId: event.metadata.eventId,
          syncHandlers: result.syncHandlerCount,
          asyncHandlers: result.asyncHandlerCount,
        },
        `[EventBus] ${event.type} dispatched successfully`,
      );
    }
  };

  const error: ErrorHook = (error: Error, event: Event) => {
    logger.error(
      {
        eventType: event.type,
        eventId: event.metadata.eventId,
        error: error.message,
      },
      `[EventBus] handler error for ${event.type}`,
    );
  };

  return { before, after, error };
}
