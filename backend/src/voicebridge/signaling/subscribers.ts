import { logger } from '../../common/logger.js';
import type { EventBus } from '../../event-bus/index.js';
import {
  SIGNALING_CONNECTED,
  SIGNALING_DISCONNECTED,
  SIGNALING_MESSAGE_RECEIVED,
  SIGNALING_FAILED,
} from './events.js';
import type {
  SignalingConnectedPayload,
  SignalingDisconnectedPayload,
  SignalingMessageReceivedPayload,
  SignalingFailedPayload,
} from './events.js';

export function registerSignalingSubscribers(eventBus: EventBus): void {
  eventBus.subscribe<SignalingConnectedPayload>(
    SIGNALING_CONNECTED,
    async (event) => {
      const { userId } = event.payload;
      logger.info({ userId }, '[EventBus] SignalingConnected received');
    },
    { name: 'signaling.connected-validator', scope: 'signaling' },
  );

  eventBus.subscribe<SignalingDisconnectedPayload>(
    SIGNALING_DISCONNECTED,
    async (event) => {
      const { userId } = event.payload;
      logger.info({ userId }, '[EventBus] SignalingDisconnected received');
    },
    { name: 'signaling.disconnected-logger', scope: 'signaling' },
  );

  eventBus.subscribe<SignalingMessageReceivedPayload>(
    SIGNALING_MESSAGE_RECEIVED,
    async (event) => {
      const { userId, messageType, size } = event.payload;
      logger.info({ userId, messageType, size }, '[EventBus] SignalingMessageReceived received');
    },
    { name: 'signaling.message-received-observer', scope: 'signaling' },
  );

  eventBus.subscribe<SignalingFailedPayload>(
    SIGNALING_FAILED,
    async (event) => {
      const { userId, reason } = event.payload;
      logger.warn({ userId, reason }, '[EventBus] SignalingFailed received');
    },
    { name: 'signaling.failed-logger', scope: 'signaling' },
  );
}
