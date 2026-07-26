import type { EventBus } from '../../event-bus/index.js';
import { install } from './publisher.js';
import { registerPresenceSubscribers } from './subscribers.js';

export function register(eventBus: EventBus): void {
  install(eventBus);
  registerPresenceSubscribers(eventBus);
}

export {
  publishPresenceConnected,
  publishPresenceDisconnected,
  publishPresenceUpdated,
} from './publisher.js';

export type {
  PresenceConnectedPayload,
  PresenceDisconnectedPayload,
  PresenceUpdatedPayload,
} from './events.js';
