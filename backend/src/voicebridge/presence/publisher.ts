import { createEventPublisher } from '../../common/event-publisher.js';
import type { EventBus } from '../../event-bus/index.js';
import {
  PRESENCE_CONNECTED,
  PRESENCE_DISCONNECTED,
  PRESENCE_UPDATED,
} from './events.js';
import type {
  PresenceConnectedPayload,
  PresenceDisconnectedPayload,
  PresenceUpdatedPayload,
} from './events.js';

const publisher = createEventPublisher('voicebridge.presence', 1);

export function install(eventBus: EventBus): void {
  publisher.install(eventBus);
}

export const publishPresenceConnected = (userId: string): void =>
  publisher.publish<PresenceConnectedPayload>(PRESENCE_CONNECTED, { userId });

export const publishPresenceDisconnected = (userId: string): void =>
  publisher.publish<PresenceDisconnectedPayload>(PRESENCE_DISCONNECTED, { userId });

export const publishPresenceUpdated = (userId: string): void =>
  publisher.publish<PresenceUpdatedPayload>(PRESENCE_UPDATED, { userId });
