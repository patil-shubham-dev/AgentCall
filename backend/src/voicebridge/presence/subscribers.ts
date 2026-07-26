import { logger } from '../../common/logger.js';
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

export function registerPresenceSubscribers(eventBus: EventBus): void {
  eventBus.subscribe<PresenceConnectedPayload>(
    PRESENCE_CONNECTED,
    async (event) => {
      const { userId } = event.payload;
      logger.info(
        { userId },
        '[EventBus] PresenceConnected received — mirroring presence registration',
      );
    },
    { name: 'presence.connected-validator', scope: 'presence' },
  );

  eventBus.subscribe<PresenceDisconnectedPayload>(
    PRESENCE_DISCONNECTED,
    async (event) => {
      const { userId } = event.payload;
      logger.info(
        { userId },
        '[EventBus] PresenceDisconnected received',
      );
    },
    { name: 'presence.disconnected-logger', scope: 'presence' },
  );

  eventBus.subscribe<PresenceUpdatedPayload>(
    PRESENCE_UPDATED,
    async (event) => {
      const { userId } = event.payload;
      logger.info(
        { userId },
        '[EventBus] PresenceUpdated received — connection replaced',
      );
    },
    { name: 'presence.updated-logger', scope: 'presence' },
  );
}
