import type { EventBus } from '../../event-bus/index.js';
import { install } from './publisher.js';
import { registerCallSubscribers } from './subscribers.js';

export function register(eventBus: EventBus): void {
  install(eventBus);
  registerCallSubscribers(eventBus);
}

export {
  publishCallCreated,
  publishCallAnswered,
  publishCallPaused,
  publishCallEnded,
  publishCallCancelled,
  publishCallResumed,
  publishCallDeleted,
  publishCallExpired,
  publishCallAborted,
} from './publisher.js';

export type {
  CallCreatedPayload,
  CallAnsweredPayload,
  CallPausedPayload,
  CallEndedPayload,
  CallCancelledPayload,
  CallResumedPayload,
  CallDeletedPayload,
  CallExpiredPayload,
  CallAbortedPayload,
} from './events.js';
