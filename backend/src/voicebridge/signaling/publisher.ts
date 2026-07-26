import { createEventPublisher } from '../../common/event-publisher.js';
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

const publisher = createEventPublisher('voicebridge.signaling', 1);

export function install(eventBus: EventBus): void {
  publisher.install(eventBus);
}

export const publishSignalingConnected = (userId: string): void =>
  publisher.publish<SignalingConnectedPayload>(SIGNALING_CONNECTED, { userId });

export const publishSignalingDisconnected = (userId: string): void =>
  publisher.publish<SignalingDisconnectedPayload>(SIGNALING_DISCONNECTED, { userId });

export const publishSignalingMessageReceived = (userId: string, messageType: string, size: number): void =>
  publisher.publish<SignalingMessageReceivedPayload>(SIGNALING_MESSAGE_RECEIVED, { userId, messageType, size });

export const publishSignalingFailed = (userId: string, reason: string): void =>
  publisher.publish<SignalingFailedPayload>(SIGNALING_FAILED, { userId, reason });
